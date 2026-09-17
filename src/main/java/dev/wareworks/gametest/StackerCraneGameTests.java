package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.AISLE_16X10X7;
import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;
import static dev.wareworks.gametest.WareworksGameTests.RAIL_LINE_48X5X3;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.api.stress.BlockStressValues;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.AisleLayout;
import dev.wareworks.content.crane.MastHeightValueBox;
import dev.wareworks.content.crane.RailScan;
import dev.wareworks.content.crane.StackerCraneBlock;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.address.StorageAddress;
import dev.wareworks.registry.WareworksBlockEntityTypes;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.registry.WareworksTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests of the aisle core: warehouse rail counting, mast height, kinetic input of the stacker crane dock, aisle
 * layout mapping, placement, persistence and sync, registration and removal.
 * <p>
 * Aisle layouts use the {@code aisle_16x10x7} floor (dock at x = 1, z = 3, aisle along +X) and the
 * {@code rail_line_48x5x3} strip for the length cap; kinetic tests power the dock with a Create creative motor placed
 * directly below it and facing up. Assertions in sequences only use {@code helper.fail/assert*}, and block entities are
 * looked up through typed accessors, because any other exception there would crash the whole test server.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class StackerCraneGameTests {
    /** Dock position in {@code aisle_16x10x7}; the aisle runs along +X, rails at x = 2..15. */
    private static final BlockPos AISLE_DOCK = new BlockPos(1, BASE_Y, 3);
    /** Dock position in {@code rail_line_48x5x3}; rails at x = 1..47. */
    private static final BlockPos RAIL_LINE_DOCK = new BlockPos(0, BASE_Y, 1);
    private static final int RAIL_LINE_MAX_RAILS = 47;
    private static final BlockPos CENTER = new BlockPos(3, BASE_Y, 3);

    private static final int RAILS = 5;
    private static final int MORE_RAILS = 9;
    private static final int SMALL_CAP = 3;
    private static final int MAST_HEIGHT = 6;
    private static final int OVERSIZED_MAST_HEIGHT = 10_000;
    private static final int TEST_RPM = 64;
    private static final int KINETIC_START_TICKS = 2;
    private static final int KINETIC_SETTLE_TICKS = 10;
    private static final int PERIODIC_REFRESH_TIMEOUT_TICKS = 300;
    private static final int LAYOUT_LENGTH = 5;
    private static final int LAYOUT_HEIGHT = 3;
    private static final char LAYOUT_LETTER = 'B';
    /** Lateral extent of the rack bounds: left rack plane, aisle line, right rack plane. */
    private static final int RACK_BOUNDS_WIDTH = 3;
    /** Horizontal offset that is far outside every loaded test area. */
    private static final int UNLOADED_OFFSET = 20_000_000;
    private static final int UNLOADED_PREVIOUS_LENGTH = 7;
    private static final float[] PLAYER_YAWS = {0f, 90f, 180f, 270f};
    private static final double PIXELS_PER_BLOCK = 16.0;
    private static final double HALF_BLOCK = 0.5;
    private static final double SHAPE_EPSILON = 1.0E-6;
    /** The end stop is 1 px deep at the block edge: its centre is at least this far from the block centre. */
    private static final double END_STOP_MIN_OFFSET = 0.4;

    private StackerCraneGameTests() {
    }

    // --- rails and geometry --------------------------------------------------------------------------------------

    /**
     * n consecutive rails give length n (a rail behind the dock does not count); more rails and a removed rail are
     * picked up by the next refresh.
     */
    @GameTest(template = AISLE_16X10X7)
    public static void craneRailCount(GameTestHelper helper) {
        placeDock(helper, AISLE_DOCK, Direction.EAST);
        placeRails(helper, AISLE_DOCK, Direction.EAST, 1, RAILS, Direction.Axis.X);
        helper.setBlock(AISLE_DOCK.west(), rail(Direction.Axis.X));

        helper.startSequence()
                .thenWaitUntil(() -> assertLength(helper, AISLE_DOCK, RAILS, "rails counted on load"))
                .thenExecute(() -> {
                    StackerCraneBlockEntity dock = dockAt(helper, AISLE_DOCK);
                    helper.assertValueEqual(dock.geometry(), AisleGeometry.of(RAILS, defaultMastHeight()),
                            "default geometry");
                    placeRails(helper, AISLE_DOCK, Direction.EAST, RAILS + 1, MORE_RAILS, Direction.Axis.X);
                    helper.assertTrue(dock.refreshGeometry(), "added rails change the geometry");
                    helper.assertValueEqual(dock.aisleLength(), MORE_RAILS, "length after adding rails");
                    helper.assertFalse(dock.refreshGeometry(), "a second refresh changes nothing");

                    helper.setBlock(railPos(AISLE_DOCK, Direction.EAST, 3), Blocks.AIR);
                    helper.assertTrue(dock.refreshGeometry(), "a removed rail changes the geometry");
                    helper.assertValueEqual(dock.aisleLength(), 2, "a gap ends the aisle");
                })
                .thenSucceed();
    }

    /** Gaps, rails across the aisle and other blocks stop the count; rotating the dock re-counts in the new direction. */
    @GameTest(template = AISLE_16X10X7)
    public static void craneRailGapAndAxis(GameTestHelper helper) {
        placeDock(helper, AISLE_DOCK, Direction.EAST);
        placeRails(helper, AISLE_DOCK, Direction.EAST, 1, 3, Direction.Axis.X);
        placeRails(helper, AISLE_DOCK, Direction.EAST, 5, 8, Direction.Axis.X);

        helper.startSequence()
                .thenWaitUntil(() -> assertLength(helper, AISLE_DOCK, 3, "gap at position 4"))
                .thenExecute(() -> {
                    StackerCraneBlockEntity dock = dockAt(helper, AISLE_DOCK);
                    helper.setBlock(railPos(AISLE_DOCK, Direction.EAST, 4), rail(Direction.Axis.Z));
                    dock.refreshGeometry();
                    helper.assertValueEqual(dock.aisleLength(), 3, "a rail across the aisle stops the count");

                    helper.setBlock(railPos(AISLE_DOCK, Direction.EAST, 4), rail(Direction.Axis.X));
                    dock.refreshGeometry();
                    helper.assertValueEqual(dock.aisleLength(), 8, "gap closed with a rail along the aisle");

                    helper.setBlock(railPos(AISLE_DOCK, Direction.EAST, 2), Blocks.STONE);
                    dock.refreshGeometry();
                    helper.assertValueEqual(dock.aisleLength(), 1, "another block stops the count");

                    // Same block, new facing: the block entity stays and re-counts on its next tick.
                    helper.setBlock(AISLE_DOCK, dockState(Direction.WEST));
                    helper.assertTrue(dockAt(helper, AISLE_DOCK) == dock, "rotation keeps the block entity");
                })
                .thenWaitUntil(() -> assertLength(helper, AISLE_DOCK, 0, "facing west, no rails behind the dock"))
                .thenSucceed();
    }

    /** The length is capped at maxAisleLength (the configured value and a smaller explicit scan limit). */
    @GameTest(template = RAIL_LINE_48X5X3)
    public static void craneRailCap(GameTestHelper helper) {
        int cap = WareworksConfig.maxAisleLength();
        if (cap + 1 > RAIL_LINE_MAX_RAILS)
            helper.fail("maxAisleLength " + cap + " does not fit the rail line template (" + RAIL_LINE_MAX_RAILS
                    + " rails)");
        int placed = Math.min(cap + 2, RAIL_LINE_MAX_RAILS);
        placeDock(helper, RAIL_LINE_DOCK, Direction.EAST);
        placeRails(helper, RAIL_LINE_DOCK, Direction.EAST, 1, placed, Direction.Axis.X);

        helper.startSequence()
                .thenWaitUntil(() -> assertLength(helper, RAIL_LINE_DOCK, cap, "capped at maxAisleLength"))
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    BlockPos dock = helper.absolutePos(RAIL_LINE_DOCK);
                    helper.assertValueEqual(RailScan.scan(level, dock, Direction.EAST, SMALL_CAP),
                            new RailScan(SMALL_CAP, false), "explicit scan limit");
                    helper.assertValueEqual(RailScan.scan(level, dock, Direction.EAST, 0), new RailScan(0, false),
                            "limit zero");
                    helper.assertValueEqual(RailScan.scan(level, dock, Direction.EAST, AisleGeometry.MAX_LENGTH),
                            new RailScan(placed, false), "uncapped scan finds every rail");
                    helper.assertValueEqual(RailScan.scan(level, dock, Direction.WEST, SMALL_CAP),
                            new RailScan(0, false), "no rails in the other direction");
                })
                .thenSucceed();
    }

    /**
     * Without a request the dock re-counts within geometryRefreshTicks; requestGeometryRefresh re-counts on the next
     * tick. A scan that reaches an unloaded chunk keeps the known length.
     */
    @GameTest(template = AISLE_16X10X7, timeoutTicks = PERIODIC_REFRESH_TIMEOUT_TICKS)
    public static void craneGeometryRefresh(GameTestHelper helper) {
        placeDock(helper, AISLE_DOCK, Direction.EAST);
        placeRails(helper, AISLE_DOCK, Direction.EAST, 1, 2, Direction.Axis.X);
        long[] placedAt = new long[1];

        helper.startSequence()
                .thenWaitUntil(() -> assertLength(helper, AISLE_DOCK, 2, "initial count"))
                .thenExecute(() -> {
                    placeRails(helper, AISLE_DOCK, Direction.EAST, 3, RAILS, Direction.Axis.X);
                    placedAt[0] = helper.getTick();
                })
                .thenWaitUntil(() -> assertLength(helper, AISLE_DOCK, RAILS, "periodic refresh"))
                .thenExecute(() -> {
                    long elapsed = helper.getTick() - placedAt[0];
                    helper.assertTrue(elapsed <= WareworksConfig.geometryRefreshTicks() + 1,
                            "periodic refresh took " + elapsed + " ticks");
                    placeRails(helper, AISLE_DOCK, Direction.EAST, RAILS + 1, MORE_RAILS, Direction.Axis.X);
                    dockAt(helper, AISLE_DOCK).requestGeometryRefresh();
                })
                .thenWaitUntil(1, () -> assertLength(helper, AISLE_DOCK, MORE_RAILS, "requested refresh"))
                .thenExecute(() -> {
                    BlockPos dock = helper.absolutePos(AISLE_DOCK);
                    int farX = dock.getX() > 0 ? dock.getX() - UNLOADED_OFFSET : dock.getX() + UNLOADED_OFFSET;
                    BlockPos farAway = new BlockPos(farX, dock.getY(), dock.getZ());
                    helper.assertFalse(helper.getLevel().isLoaded(farAway.east()), "test position must be unloaded");
                    RailScan scan = RailScan.scan(helper.getLevel(), farAway, Direction.EAST, SMALL_CAP);
                    helper.assertValueEqual(scan, new RailScan(0, true), "scan stops at an unloaded chunk");
                    helper.assertValueEqual(scan.resolveLength(UNLOADED_PREVIOUS_LENGTH, WareworksConfig.maxAisleLength()),
                            UNLOADED_PREVIOUS_LENGTH, "known length kept behind an unloaded chunk");
                })
                .thenSucceed();
    }

    /** The mast height clamps to 1..maxMastHeight and updates the geometry immediately. */
    @GameTest(template = EMPTY_7X5X7)
    public static void craneMastHeight(GameTestHelper helper) {
        placeDock(helper, CENTER, Direction.NORTH);

        helper.startSequence()
                .thenIdle(1)
                .thenExecute(() -> {
                    StackerCraneBlockEntity dock = dockAt(helper, CENTER);
                    ScrollValueBehaviour mast = mastBehaviour(helper, dock);
                    int max = WareworksConfig.maxMastHeight();
                    helper.assertValueEqual(dock.mastHeight(), defaultMastHeight(), "default mast height");

                    mast.setValue(0);
                    helper.assertValueEqual(mast.getValue(), 1, "clamped to the minimum");
                    helper.assertValueEqual(dock.geometry().height(), 1, "geometry follows at once");

                    mast.setValue(OVERSIZED_MAST_HEIGHT);
                    helper.assertValueEqual(mast.getValue(), max, "clamped to maxMastHeight");
                    helper.assertValueEqual(dock.geometry().height(), max, "geometry at the maximum");
                    helper.assertValueEqual((int) dock.layout().bounds().getYsize(), max, "bounds cover the mast");

                    int height = Math.min(MAST_HEIGHT, max);
                    mast.setValue(height);
                    helper.assertValueEqual(dock.geometry(), AisleGeometry.of(0, height), "height in range");

                    // A value past the limit (e.g. after lowering maxMastHeight) is clamped when it is read, and the
                    // stored number survives, so raising the limit again brings it back (warehouse-system.md §8.1).
                    mast.value = max + 1;
                    dock.refreshGeometry();
                    helper.assertValueEqual(dock.mastHeight(), max, "the effective height is clamped to maxMastHeight");
                    helper.assertValueEqual(mast.getValue(), max + 1, "the stored value is not overwritten");
                })
                .thenSucceed();
    }

    /**
     * Outline and collision of the dock follow its low rail bed model for every facing ({@code docs/stacker-crane.md} §2.1):
     * {@value StackerCraneBlock#BED_HEIGHT_PIXELS} px high with the end stop at the controller side, never a full cube. The
     * "Mast Height" value box answers on the top face of the bed and nowhere else (no box floating at full-cube faces).
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void craneShapeIsTheBed(GameTestHelper helper) {
        placeDock(helper, CENTER, Direction.NORTH);
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(CENTER);
        double bedTop = StackerCraneBlock.BED_HEIGHT_PIXELS / PIXELS_PER_BLOCK;
        double endStopTop = StackerCraneBlock.END_STOP_HEIGHT_PIXELS / PIXELS_PER_BLOCK;
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockState state = dockState(facing);
            VoxelShape shape = state.getShape(level, pos);
            AABB bounds = shape.bounds();
            // VoxelShaper rotates with trigonometry, so turned shapes are exact only up to rounding noise.
            helper.assertTrue(near(bounds.minX, 0) && near(bounds.minZ, 0) && near(bounds.maxX, 1) && near(bounds.maxZ, 1)
                    && near(bounds.minY, 0), facing + ": the bed covers the whole block floor, but is " + bounds);
            helper.assertTrue(near(bounds.maxY, endStopTop), facing + ": shape top is the end stop, but is " + bounds.maxY);
            helper.assertFalse(state.isCollisionShapeFullBlock(level, pos), facing + ": no full-block collision");
            helper.assertValueEqual(state.getCollisionShape(level, pos).bounds(), bounds, facing + ": collision = outline");
            Direction controllerSide = facing.getOpposite();
            for (AABB box : shape.toAabbs()) {
                if (box.maxY <= bedTop + SHAPE_EPSILON)
                    continue;
                Vec3 center = box.getCenter().subtract(HALF_BLOCK, HALF_BLOCK, HALF_BLOCK);
                double towardsController = center.x * controllerSide.getStepX() + center.z * controllerSide.getStepZ();
                helper.assertTrue(towardsController > END_STOP_MIN_OFFSET,
                        facing + ": only the end stop at the controller side rises above the bed, but found " + box);
            }
        }

        helper.startSequence()
                .thenIdle(1)
                .thenExecute(() -> {
                    BlockState state = dockAt(helper, CENTER).getBlockState();
                    Vec3 bedTopCenter = new Vec3(HALF_BLOCK, bedTop, HALF_BLOCK);
                    helper.assertTrue(mastBehaviour(helper, dockAt(helper, CENTER))
                            .testHit(Vec3.atLowerCornerOf(pos).add(bedTopCenter)), "the dock's value box is on the bed top");
                    MastHeightValueBox box = new MastHeightValueBox();
                    helper.assertTrue(box.fromSide(Direction.UP).testHit(level, pos, state, bedTopCenter), "bed top hit");
                    helper.assertFalse(box.fromSide(Direction.UP).testHit(level, pos, state, new Vec3(HALF_BLOCK, 1, HALF_BLOCK)),
                            "no value box at the top of a full cube");
                    for (Direction side : Direction.values()) {
                        if (side == Direction.UP)
                            continue;
                        Vec3 faceCenter = new Vec3(HALF_BLOCK + side.getStepX() * HALF_BLOCK, bedTop / 2 + side.getStepY() * bedTop / 2,
                                HALF_BLOCK + side.getStepZ() * HALF_BLOCK);
                        helper.assertFalse(box.fromSide(side).testHit(level, pos, state, faceCenter), "no value box on " + side);
                    }
                })
                .thenSucceed();
    }

    // --- kinetics ------------------------------------------------------------------------------------------------

    /** A creative motor directly below drives the dock, which applies the configured stress impact. */
    @GameTest(template = EMPTY_7X5X7)
    public static void craneKineticFromBelow(GameTestHelper helper) {
        BlockPos motorPos = CENTER;
        BlockPos dockPos = CENTER.above();
        placeMotor(helper, motorPos, Direction.UP);
        placeDock(helper, dockPos, Direction.EAST);

        helper.startSequence()
                .thenIdle(KINETIC_START_TICKS)
                .thenExecute(() -> motorAt(helper, motorPos).generatedSpeed.setValue(TEST_RPM))
                .thenWaitUntil(() -> helper.assertTrue(dockAt(helper, dockPos).getSpeed() != 0,
                        "the dock must receive rotation from the motor below"))
                .thenExecute(() -> {
                    StackerCraneBlockEntity dock = dockAt(helper, dockPos);
                    helper.assertValueEqual(Math.abs(dock.getSpeed()), (float) TEST_RPM, "dock speed");
                    helper.assertValueEqual(dock.calculateStressApplied(), (float) WareworksConfig.stressImpact(),
                            "stress impact per RPM from the config");
                    helper.assertFalse(dock.isOverStressed(), "a creative motor is not overstressed by the dock");
                })
                .thenSucceed();
    }

    /** Motors at the sides and on top, with their shafts pointing at the dock, do not connect. */
    @GameTest(template = EMPTY_7X5X7)
    public static void craneNoSideInput(GameTestHelper helper) {
        placeDock(helper, CENTER, Direction.NORTH);
        List<Direction> sides = List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP);
        for (Direction side : sides)
            placeMotor(helper, CENTER.relative(side), side.getOpposite());

        helper.startSequence()
                .thenIdle(KINETIC_START_TICKS)
                .thenExecute(() -> {
                    for (Direction side : sides)
                        motorAt(helper, CENTER.relative(side)).generatedSpeed.setValue(TEST_RPM);
                })
                .thenIdle(KINETIC_SETTLE_TICKS)
                .thenExecute(() -> {
                    for (Direction side : sides)
                        helper.assertTrue(motorAt(helper, CENTER.relative(side)).getSpeed() != 0,
                                "motor " + side + " of the dock must run");
                    StackerCraneBlockEntity dock = dockAt(helper, CENTER);
                    helper.assertValueEqual(dock.getSpeed(), 0f, "no rotation through the sides or the top");
                    helper.assertFalse(dock.hasSource(), "no kinetic source");

                    BlockState state = helper.getBlockState(CENTER);
                    StackerCraneBlock block = WareworksBlocks.STACKER_CRANE.get();
                    for (Direction face : Direction.values())
                        helper.assertValueEqual(block.hasShaftTowards(helper.getLevel(), helper.absolutePos(CENTER),
                                state, face), face == Direction.DOWN, "shaft towards " + face);
                    helper.assertValueEqual(block.getRotationAxis(state), Direction.Axis.Y, "rotation axis");
                })
                .thenSucceed();
    }

    // --- layout --------------------------------------------------------------------------------------------------

    /** rackPos and worldToLocal are inverse for all four facings; non-rack positions map to nothing. */
    @GameTest(template = EMPTY_7X5X7)
    public static void aisleLayoutRoundTrip(GameTestHelper helper) {
        BlockPos dock = helper.absolutePos(CENTER);
        AisleGeometry geometry = AisleGeometry.of(LAYOUT_LENGTH, LAYOUT_HEIGHT);
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            AisleLayout layout = AisleLayout.of(dock, facing, geometry).withLetter(LAYOUT_LETTER);
            helper.assertValueEqual(layout.sideDirection(Side.LEFT), facing.getCounterClockWise(), "LEFT of " + facing);
            helper.assertValueEqual(layout.sideDirection(Side.RIGHT), facing.getClockWise(), "RIGHT of " + facing);
            AABB bounds = layout.bounds();
            Set<BlockPos> seen = new HashSet<>();
            for (RackPosition rack : geometry.rackPositions()) {
                BlockPos pos = layout.rackPos(rack);
                Direction sideDirection = rack.side() == Side.LEFT ? facing.getCounterClockWise() : facing.getClockWise();
                BlockPos expected = dock.offset(facing.getStepX() * rack.x() + sideDirection.getStepX(), rack.y(),
                        facing.getStepZ() * rack.x() + sideDirection.getStepZ());
                helper.assertValueEqual(pos, expected, "rackPos " + rack + " facing " + facing);
                helper.assertValueEqual(layout.worldToLocal(pos), Optional.of(rack), "round trip " + rack + " " + facing);
                helper.assertTrue(seen.add(pos), "distinct world positions " + rack + " " + facing);
                helper.assertTrue(bounds.contains(Vec3.atCenterOf(pos)), "bounds contain " + rack + " " + facing);
                helper.assertValueEqual(layout.addressOf(pos), Optional.of(StorageAddress.of(LAYOUT_LETTER, rack)),
                        "address of " + rack);
            }

            for (int x = 0; x <= LAYOUT_LENGTH; x++) {
                for (int y = 0; y < LAYOUT_HEIGHT; y++)
                    helper.assertTrue(layout.worldToLocal(layout.aislePos(x).above(y)).isEmpty(),
                            "the aisle line is no rack position: x=" + x + " y=" + y + " " + facing);
            }
            for (Side side : Side.values()) {
                Direction out = layout.sideDirection(side);
                List<BlockPos> outside = List.of(
                        layout.rackPos(LAYOUT_LENGTH + 1, 0, side),
                        dock.relative(facing, -1).relative(out),
                        layout.rackPos(0, 0, side).below(),
                        layout.rackPos(0, LAYOUT_HEIGHT, side),
                        layout.rackPos(2, 1, side).relative(out));
                for (BlockPos pos : outside) {
                    helper.assertTrue(layout.worldToLocal(pos).isEmpty(), "outside the aisle: " + pos + " " + facing);
                    helper.assertTrue(layout.addressOf(pos).isEmpty(), "no address outside: " + pos);
                }
            }

            int along = (int) (facing.getAxis() == Direction.Axis.X ? bounds.getXsize() : bounds.getZsize());
            int across = (int) (facing.getAxis() == Direction.Axis.X ? bounds.getZsize() : bounds.getXsize());
            helper.assertValueEqual(along, LAYOUT_LENGTH + 1, "bounds along " + facing);
            helper.assertValueEqual(across, RACK_BOUNDS_WIDTH, "bounds across " + facing);
            helper.assertValueEqual((int) bounds.getYsize(), LAYOUT_HEIGHT, "bounds height " + facing);
        }

        // warehouse-system.md §1 diagram: aisle east, LEFT is north (z - 1), RIGHT is south (z + 1).
        AisleLayout east = AisleLayout.of(dock, Direction.EAST, geometry);
        helper.assertValueEqual(east.rackPos(2, 1, Side.LEFT), dock.offset(2, 1, -1), "east LEFT");
        helper.assertValueEqual(east.rackPos(2, 1, Side.RIGHT), dock.offset(2, 1, 1), "east RIGHT");
        helper.assertTrue(east.addressOf(east.rackPos(0, 0, Side.LEFT)).isEmpty(), "no letter, no address");
        helper.assertValueEqual(east.withLetter('A').addressOf(east.rackPos(LAYOUT_LENGTH, 2, Side.RIGHT))
                .map(StorageAddress::format), Optional.of("A-03-05R"), "address format");

        AisleLayout dockOnly = AisleLayout.of(dock, Direction.SOUTH, AisleGeometry.of(0, 1));
        for (RackPosition rack : dockOnly.geometry().rackPositions())
            helper.assertValueEqual(dockOnly.worldToLocal(dockOnly.rackPos(rack)), Optional.of(rack), "dock only");
        helper.assertTrue(dockOnly.worldToLocal(dockOnly.rackPos(1, 0, Side.LEFT)).isEmpty(), "no rails, no position 1");

        try {
            AisleLayout.of(dock, Direction.UP, geometry);
            helper.fail("a vertical aisle direction must be rejected");
        } catch (IllegalArgumentException expected) {
            // rejected as it should be
        }
        helper.succeed();
    }

    // --- placement, persistence, registration, removal ------------------------------------------------------------

    /** The dock faces the player's look direction; a rail runs along it. */
    @GameTest(template = EMPTY_7X5X7)
    public static void cranePlacement(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos floor = helper.absolutePos(CENTER.below());
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        for (float yaw : PLAYER_YAWS) {
            player.setYRot(yaw);
            Direction look = Direction.fromYRot(yaw);
            BlockState dock = WareworksBlocks.STACKER_CRANE.get()
                    .getStateForPlacement(floorClick(level, player, floor, WareworksBlocks.STACKER_CRANE.asStack()));
            helper.assertValueEqual(dock.getValue(HorizontalKineticBlock.HORIZONTAL_FACING), look,
                    "dock facing for yaw " + yaw);
            BlockState rail = WareworksBlocks.WAREHOUSE_RAIL.get()
                    .getStateForPlacement(floorClick(level, player, floor, WareworksBlocks.WAREHOUSE_RAIL.asStack()));
            helper.assertValueEqual(rail.getValue(WarehouseRailBlock.AXIS), look.getAxis(), "rail axis for yaw " + yaw);
            helper.assertFalse(rail.getValue(WarehouseRailBlock.WATERLOGGED), "not waterlogged in air");
        }
        helper.succeed();
    }

    /** Aisle length and mast height survive a save; the client packet also carries the controller link flag. */
    @GameTest(template = AISLE_16X10X7)
    public static void cranePersistenceAndSync(GameTestHelper helper) {
        placeDock(helper, AISLE_DOCK, Direction.EAST);
        placeRails(helper, AISLE_DOCK, Direction.EAST, 1, 3, Direction.Axis.X);

        helper.startSequence()
                .thenWaitUntil(() -> assertLength(helper, AISLE_DOCK, 3, "rails counted"))
                .thenExecute(() -> {
                    StackerCraneBlockEntity dock = dockAt(helper, AISLE_DOCK);
                    int height = Math.min(MAST_HEIGHT, WareworksConfig.maxMastHeight());
                    mastBehaviour(helper, dock).setValue(height);
                    AisleGeometry expected = AisleGeometry.of(3, height);
                    helper.assertValueEqual(dock.geometry(), expected, "live geometry");

                    HolderLookup.Provider registries = helper.getLevel().registryAccess();
                    CompoundTag saved = dock.saveWithFullMetadata(registries);
                    helper.assertFalse(saved.contains(StackerCraneBlockEntity.CONTROLLER_LINKED_TAG),
                            "the controller link is not saved");
                    helper.assertValueEqual(loadCopy(helper, dock, saved).geometry(), expected, "geometry after load");

                    dock.linkController(dock.getBlockPos().relative(dock.facing().getOpposite()));
                    StackerCraneBlockEntity client = detachedCopy(helper, dock);
                    helper.assertFalse(client.isControllerLinked(), "a fresh copy is not linked");
                    client.handleUpdateTag(dock.getUpdateTag(registries), registries);
                    helper.assertValueEqual(client.geometry(), expected, "geometry after client sync");
                    helper.assertTrue(client.isControllerLinked(), "controller link after client sync");

                    CompoundTag broken = saved.copy();
                    broken.putInt(StackerCraneBlockEntity.AISLE_LENGTH_TAG, -5);
                    broken.remove("ScrollValue");
                    StackerCraneBlockEntity sanitized = loadCopy(helper, dock, broken);
                    helper.assertValueEqual(sanitized.geometry(), AisleGeometry.of(0, defaultMastHeight()),
                            "negative length and missing mast height");

                    broken.putString(StackerCraneBlockEntity.AISLE_LENGTH_TAG, "long");
                    broken.putInt("ScrollValue", Integer.MAX_VALUE);
                    StackerCraneBlockEntity clamped = loadCopy(helper, dock, broken);
                    // Loading sanitizes the stored value to the record maximum, and reading it applies the configured
                    // maximum on top, so untrusted NBT can never produce a taller mast than the config allows.
                    helper.assertValueEqual(clamped.geometry(), AisleGeometry.of(0, WareworksConfig.maxMastHeight()),
                            "wrong length type and oversized mast height");
                    helper.assertTrue(clamped.mastHeight() <= AisleGeometry.MAX_HEIGHT,
                            "the loaded height stays inside the geometry record's own range");
                    broken.putInt(StackerCraneBlockEntity.AISLE_LENGTH_TAG, Integer.MAX_VALUE);
                    helper.assertValueEqual(loadCopy(helper, dock, broken).aisleLength(), AisleGeometry.MAX_LENGTH,
                            "oversized length clamped");
                })
                .thenSucceed();
    }

    /** Tags, stress registration, block entity and wrench/structure rotation of dock and rail. */
    @GameTest(template = EMPTY_7X5X7)
    public static void craneRegistration(GameTestHelper helper) {
        BlockState dock = dockState(Direction.NORTH);
        helper.assertTrue(dock.is(BlockTags.MINEABLE_WITH_PICKAXE), "dock mineable with a pickaxe");
        helper.assertTrue(dock.is(WareworksTags.NON_MOVABLE), "dock is create:non_movable");
        helper.assertTrue(dock.is(WareworksTags.RELOCATION_NOT_SUPPORTED), "dock is c:relocation_not_supported");
        helper.assertTrue(dock.hasBlockEntity(), "dock has a block entity");
        helper.assertTrue(WareworksBlockEntityTypes.STACKER_CRANE.get().isValid(dock), "block entity type is valid");
        helper.assertValueEqual(BlockStressValues.getImpact(WareworksBlocks.STACKER_CRANE.get()),
                WareworksConfig.stressImpact(), "registered stress impact");

        StackerCraneBlock dockBlock = WareworksBlocks.STACKER_CRANE.get();
        helper.assertValueEqual(dockBlock.getRotatedBlockState(dock, Direction.UP)
                .getValue(HorizontalKineticBlock.HORIZONTAL_FACING), Direction.EAST, "wrench rotates clockwise");
        helper.assertValueEqual(dock.rotate(Rotation.CLOCKWISE_180).getValue(HorizontalKineticBlock.HORIZONTAL_FACING),
                Direction.SOUTH, "structure rotation");

        BlockState rail = rail(Direction.Axis.X);
        helper.assertTrue(rail.is(BlockTags.MINEABLE_WITH_PICKAXE), "rail mineable with a pickaxe");
        helper.assertFalse(rail.hasBlockEntity(), "rail has no block entity");
        helper.assertValueEqual(WareworksBlocks.WAREHOUSE_RAIL.get().getRotatedBlockState(rail, Direction.UP)
                .getValue(WarehouseRailBlock.AXIS), Direction.Axis.Z, "wrench toggles the rail axis");
        helper.assertValueEqual(rail.rotate(Rotation.COUNTERCLOCKWISE_90).getValue(WarehouseRailBlock.AXIS),
                Direction.Axis.Z, "structure rotation by 90 degrees");
        helper.assertValueEqual(rail.rotate(Rotation.CLOCKWISE_180).getValue(WarehouseRailBlock.AXIS),
                Direction.Axis.X, "structure rotation by 180 degrees");
        helper.assertTrue(WarehouseRailBlock.isRailAlong(rail, Direction.Axis.X), "rail along X");
        helper.assertFalse(WarehouseRailBlock.isRailAlong(rail, Direction.Axis.Z), "not along Z");
        helper.assertFalse(WarehouseRailBlock.isRailAlong(Blocks.RAIL.defaultBlockState(), Direction.Axis.X),
                "vanilla rails are no warehouse rails");
        helper.succeed();
    }

    /** Breaking dock and rail drops them as items and removes the dock's block entity. */
    @GameTest(template = AISLE_16X10X7)
    public static void craneBreak(GameTestHelper helper) {
        placeDock(helper, AISLE_DOCK, Direction.EAST);
        placeRails(helper, AISLE_DOCK, Direction.EAST, 1, 1, Direction.Axis.X);
        BlockPos railPos = railPos(AISLE_DOCK, Direction.EAST, 1);

        helper.startSequence()
                .thenWaitUntil(() -> assertLength(helper, AISLE_DOCK, 1, "rail counted"))
                .thenExecute(() -> {
                    StackerCraneBlockEntity dock = dockAt(helper, AISLE_DOCK);
                    helper.getLevel().destroyBlock(helper.absolutePos(AISLE_DOCK), true);
                    helper.assertBlockPresent(Blocks.AIR, AISLE_DOCK);
                    helper.assertTrue(WareworksBlockEntityTypes.STACKER_CRANE
                            .getNullable(helper.getLevel(), helper.absolutePos(AISLE_DOCK)) == null, "block entity removed");
                    helper.assertTrue(dock.isRemoved(), "the old block entity is marked removed");
                    helper.assertFalse(dock.refreshGeometry(), "a removed dock does not refresh");
                    helper.assertItemEntityPresent(WareworksBlocks.STACKER_CRANE.asItem(), AISLE_DOCK, 1.0);

                    helper.getLevel().destroyBlock(helper.absolutePos(railPos), true);
                    helper.assertBlockPresent(Blocks.AIR, railPos);
                    helper.assertItemEntityPresent(WareworksBlocks.WAREHOUSE_RAIL.asItem(), railPos, 1.0);
                })
                .thenSucceed();
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private static boolean near(double value, double expected) {
        return Math.abs(value - expected) < SHAPE_EPSILON;
    }

    private static int defaultMastHeight() {
        return Math.min(StackerCraneBlockEntity.DEFAULT_MAST_HEIGHT, WareworksConfig.maxMastHeight());
    }

    private static BlockState dockState(Direction facing) {
        return WareworksBlocks.STACKER_CRANE.getDefaultState().setValue(HorizontalKineticBlock.HORIZONTAL_FACING, facing);
    }

    private static BlockState rail(Direction.Axis axis) {
        return WareworksBlocks.WAREHOUSE_RAIL.getDefaultState().setValue(WarehouseRailBlock.AXIS, axis);
    }

    private static void placeDock(GameTestHelper helper, BlockPos pos, Direction facing) {
        helper.setBlock(pos, dockState(facing));
    }

    private static BlockPos railPos(BlockPos dock, Direction facing, int position) {
        return dock.relative(facing, position);
    }

    /** Places rails at aisle positions {@code from..to} (inclusive). */
    private static void placeRails(GameTestHelper helper, BlockPos dock, Direction facing, int from, int to,
                                   Direction.Axis axis) {
        for (int position = from; position <= to; position++)
            helper.setBlock(railPos(dock, facing, position), rail(axis));
    }

    private static void placeMotor(GameTestHelper helper, BlockPos pos, Direction shaftSide) {
        helper.setBlock(pos, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, shaftSide));
    }

    private static StackerCraneBlockEntity dockAt(GameTestHelper helper, BlockPos pos) {
        StackerCraneBlockEntity be = WareworksBlockEntityTypes.STACKER_CRANE
                .getNullable(helper.getLevel(), helper.absolutePos(pos));
        if (be == null)
            helper.fail("missing stacker crane block entity", pos);
        return be;
    }

    private static CreativeMotorBlockEntity motorAt(GameTestHelper helper, BlockPos pos) {
        CreativeMotorBlockEntity be = AllBlockEntityTypes.MOTOR.getNullable(helper.getLevel(), helper.absolutePos(pos));
        if (be == null)
            helper.fail("missing creative motor block entity", pos);
        return be;
    }

    private static ScrollValueBehaviour mastBehaviour(GameTestHelper helper, StackerCraneBlockEntity dock) {
        ScrollValueBehaviour behaviour = BlockEntityBehaviour.get(dock, ScrollValueBehaviour.TYPE);
        if (behaviour == null)
            helper.fail("stacker crane has no mast height value box");
        return behaviour;
    }

    private static void assertLength(GameTestHelper helper, BlockPos dockPos, int expected, String message) {
        helper.assertValueEqual(dockAt(helper, dockPos).aisleLength(), expected, message);
    }

    /** A level-less copy of the dock's block entity, standing in for the client-side instance. */
    private static StackerCraneBlockEntity detachedCopy(GameTestHelper helper, StackerCraneBlockEntity be) {
        StackerCraneBlockEntity copy = WareworksBlockEntityTypes.STACKER_CRANE.create(be.getBlockPos(), be.getBlockState());
        if (copy == null)
            helper.fail("could not create a detached stacker crane block entity");
        return copy;
    }

    private static StackerCraneBlockEntity loadCopy(GameTestHelper helper, StackerCraneBlockEntity be, CompoundTag tag) {
        BlockEntity loaded = BlockEntity.loadStatic(be.getBlockPos(), be.getBlockState(), tag,
                helper.getLevel().registryAccess());
        if (!(loaded instanceof StackerCraneBlockEntity dock)) {
            helper.fail("saved stacker crane must load again as a stacker crane");
            return be;
        }
        return dock;
    }

    private static BlockPlaceContext floorClick(ServerLevel level, Player player, BlockPos floor, ItemStack stack) {
        Vec3 hit = Vec3.atCenterOf(floor).add(0, 0.5, 0);
        return new BlockPlaceContext(level, player, InteractionHand.MAIN_HAND, stack,
                new BlockHitResult(hit, Direction.UP, floor, false));
    }
}
