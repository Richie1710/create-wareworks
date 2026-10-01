package dev.wareworks.gametest;

import static dev.wareworks.gametest.WareworksGameTests.BASE_Y;
import static dev.wareworks.gametest.WareworksGameTests.EMPTY_7X5X7;

import java.util.List;
import java.util.Optional;

import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;

import dev.wareworks.Wareworks;
import dev.wareworks.config.WareworksConfig;
import dev.wareworks.content.controller.WarehouseLayout;
import dev.wareworks.content.crane.RailNetworkScan;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.BranchGeometry;
import dev.wareworks.core.address.BranchLink;
import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.warehouse.NetworkStop;
import dev.wareworks.core.warehouse.RailGraph;
import dev.wareworks.core.warehouse.RailNetwork;
import dev.wareworks.registry.WareworksBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests of warehouse rail discovery (M21, issue #1, ADR-033): rails that touch connect, the dock finds them, and a
 * build this version cannot follow yields a <b>shorter valid warehouse</b> that says where it stops.
 * <p>
 * All of them build on {@code empty_7x5x7} with a dock at {@link #DOCK} facing east. The straight case is the one every
 * warehouse shipped so far is, so it also pins that the dock's own aisle length is still literally the first branch of
 * the network. The corner case is the new shape: two branches meeting at one shared block, and the block diagonally
 * inside the bend — laterally beside a <b>straight</b> rail of both aisles and no neighbour of the corner at all —
 * resolving to exactly one of them per facing.
 * <p>
 * Nothing here needs the crane to turn: discovery, addressing and ownership are decided before a crane moves, and the
 * crane still only ever drives the first branch in this version.
 */
@GameTestHolder(Wareworks.ID)
@PrefixGameTestTemplate(false)
public final class RailNetworkGameTests {
    /** The dock, facing east along z = 1. */
    private static final BlockPos DOCK = new BlockPos(1, BASE_Y, 1);
    /** Rails of the straight leg: x = 2..4 at z = 1. The last one is the corner block. */
    private static final int STRAIGHT_RAILS = 3;
    /** Rails of the second leg: (4, 2) and (4, 3), south from the corner. */
    private static final int CORNER_RAILS = 2;
    private static final int MAST_HEIGHT = 4;
    /** More junctions than any shape in this holder has, so {@code aisle.maxJunctions} is never what is under test. */
    private static final int MANY_JUNCTIONS = 64;
    private static final char LETTER = 'A';

    private RailNetworkGameTests() {
    }

    /** A straight aisle is a network of one branch, and the dock's own length is literally that branch. */
    @GameTest(template = EMPTY_7X5X7)
    public static void networkfollowsastraightaisle(GameTestHelper helper) {
        placeDock(helper, DOCK);
        placeStraightLeg(helper);

        RailNetwork network = scan(helper);
        helper.assertValueEqual(network.branchCount(), 1, "one branch");
        helper.assertValueEqual(network.firstBranchLength(), STRAIGHT_RAILS, "every rail counted");
        helper.assertValueEqual(network.rails(), STRAIGHT_RAILS, "and no other rail found");
        helper.assertValueEqual(network.stop(), NetworkStop.END, "the rails simply end");
        helper.assertTrue(network.isComplete(), "nothing was cut off");
        helper.assertValueEqual(network.geometry().firstBranch(),
                new BranchGeometry(0, 0, 0, Heading.EAST, STRAIGHT_RAILS), "the branch at the dock");
        helper.assertTrue(network.geometry().links().isEmpty(), "a straight aisle has no corner to hand over at");
        helper.assertValueEqual(dockAt(helper).aisleLength(), STRAIGHT_RAILS, "the dock's own aisle is that branch");
        helper.succeed();
    }

    /**
     * An L: two branches sharing the corner block, and the ownership rule deciding the block inside the bend.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void networkturnsacorner(GameTestHelper helper) {
        placeDock(helper, DOCK);
        placeStraightLeg(helper);
        placeCornerLeg(helper);

        RailNetwork network = scan(helper);
        helper.assertValueEqual(network.branchCount(), 2, "two branches");
        helper.assertValueEqual(network.stop(), NetworkStop.END, "the rails simply end");
        helper.assertValueEqual(network.rails(), STRAIGHT_RAILS + CORNER_RAILS, "every rail is part of a branch");
        helper.assertValueEqual(network.geometry().branch(0), new BranchGeometry(0, 0, 0, Heading.EAST, STRAIGHT_RAILS),
                "the branch at the dock");
        helper.assertValueEqual(network.geometry().branch(1),
                new BranchGeometry(1, STRAIGHT_RAILS, 0, Heading.SOUTH, CORNER_RAILS), "the branch round the bend");
        // The corner block has a legal name on both branches, which is what makes a hand-over a pure rename.
        helper.assertValueEqual(network.geometry().links(),
                List.of(new BranchLink(0, STRAIGHT_RAILS, 1, 0)), "the corner block belongs to both");
        helper.assertValueEqual(dockAt(helper).aisleLength(), STRAIGHT_RAILS,
                "the crane still only knows its own aisle in this version");

        WarehouseLayout layout = layoutOf(helper, network);
        // The block diagonally inside the bend: beside a straight rail of the first branch and of the second one, and
        // no neighbour of the corner block at all. Every rule that enumerates the corner's free faces misses it.
        BlockPos insideTheBend = helper.absolutePos(DOCK).offset(STRAIGHT_RAILS - 1, 0, 1);
        List<RackPosition> candidates = layout.candidates(insideTheBend);
        helper.assertValueEqual(candidates.size(), 2, "two aisles reach the block inside the bend");
        helper.assertValueEqual(candidates,
                List.of(new RackPosition(0, STRAIGHT_RAILS - 1, 0, Side.RIGHT), new RackPosition(1, 1, 0, Side.RIGHT)),
                "one position per aisle");
        // The rule is total: each candidate wants a different facing of the same block, so a member can satisfy at
        // most one of them.
        helper.assertValueEqual(awayDirections(layout, candidates), List.of(Direction.SOUTH, Direction.WEST),
                "and each of them wants its own facing");

        // The corner block itself is an aisle block of both branches, so it is nobody's rack position.
        helper.assertTrue(layout.candidates(helper.absolutePos(DOCK).offset(STRAIGHT_RAILS, 0, 0)).isEmpty(),
                "the corner block is no rack position");
        // Neither is any other aisle block, although the two beside the bend are laterally beside the other branch:
        // the rail just before the corner, the first rail after it, and the whole column above each of them, which is
        // exactly where the crane's mast travels (M21 review fix).
        BlockPos beforeTheCorner = helper.absolutePos(DOCK).offset(STRAIGHT_RAILS - 1, 0, 0);
        helper.assertTrue(layout.candidates(beforeTheCorner).isEmpty(),
                "the rail before the corner is no rack position of the second aisle");
        helper.assertTrue(layout.candidates(beforeTheCorner.above()).isEmpty(),
                "and neither is the mast column above it");
        BlockPos afterTheCorner = helper.absolutePos(DOCK).offset(STRAIGHT_RAILS, 0, 1);
        helper.assertTrue(layout.candidates(afterTheCorner).isEmpty(),
                "the first rail after the corner is no rack position of the first aisle");
        helper.assertTrue(layout.candidates(afterTheCorner.above()).isEmpty(),
                "and neither is the mast column above it");
        // The free faces of the corner are: each is served by exactly one of the two aisles - no dead corners.
        helper.assertValueEqual(layout.candidates(helper.absolutePos(DOCK).offset(STRAIGHT_RAILS, 0, -1)),
                List.of(new RackPosition(0, STRAIGHT_RAILS, 0, Side.LEFT)), "north of the corner is the first aisle");
        helper.assertValueEqual(layout.candidates(helper.absolutePos(DOCK).offset(STRAIGHT_RAILS + 1, 0, 0)),
                List.of(new RackPosition(1, 0, 0, Side.LEFT)), "east of the corner is the second aisle");
        helper.succeed();
    }

    /** A rail closed with the wrench is a wall, and opening it again puts the warehouse back. */
    @GameTest(template = EMPTY_7X5X7)
    public static void networkstopsataclosedrail(GameTestHelper helper) {
        placeDock(helper, DOCK);
        placeStraightLeg(helper);
        BlockPos middle = DOCK.offset(2, 0, 0);

        wrench(helper, middle);
        BlockState closed = helper.getBlockState(middle);
        helper.assertTrue(closed.getValue(WarehouseRailBlock.CLOSED), "the wrench closed the rail");
        helper.assertFalse(WarehouseRailBlock.isOpenRail(closed), "a closed rail is no aisle block");
        for (Direction side : Direction.Plane.HORIZONTAL)
            helper.assertFalse(closed.getValue(WarehouseRailBlock.connection(side)),
                    "and it draws itself connected to nothing (" + side + ")");

        RailNetwork shortened = scan(helper);
        helper.assertValueEqual(shortened.firstBranchLength(), 1, "the warehouse stops before the closed rail");
        helper.assertValueEqual(shortened.stop(), NetworkStop.CLOSED, "and says why");
        helper.assertValueEqual(stopPos(helper, shortened), helper.absolutePos(middle), "naming the rail itself");
        helper.assertValueEqual(dockAt(helper).aisleLength(), 1, "the dock's aisle is shorter too");

        wrench(helper, middle);
        helper.assertFalse(helper.getBlockState(middle).getValue(WarehouseRailBlock.CLOSED), "and it opens again");
        RailNetwork restored = scan(helper);
        helper.assertValueEqual(restored.firstBranchLength(), STRAIGHT_RAILS, "the whole aisle is back");
        helper.assertValueEqual(restored.stop(), NetworkStop.END, "with nothing in the way");
        helper.succeed();
    }

    /** Another dock on the same rails is a wall: neither warehouse swallows the other. */
    @GameTest(template = EMPTY_7X5X7)
    public static void networkstopsataseconddock(GameTestHelper helper) {
        placeDock(helper, DOCK);
        placeStraightLeg(helper);
        BlockPos other = DOCK.offset(STRAIGHT_RAILS + 1, 0, 0);
        helper.setBlock(other, dockState(Direction.WEST));

        RailNetwork network = scan(helper);
        helper.assertValueEqual(network.firstBranchLength(), STRAIGHT_RAILS, "the rails up to the other dock");
        helper.assertValueEqual(network.stop(), NetworkStop.SECOND_DOCK, "reported, not swallowed");
        helper.assertValueEqual(stopPos(helper, network), helper.absolutePos(other), "naming the other dock");

        // The other dock faces back along the same rails and gets the same rails from its own end: two warehouses on
        // one line of rails, exactly as two opposing aisles already were before M21.
        RailNetwork fromTheOtherEnd = RailNetworkScan.scan(helper.getLevel(), helper.absolutePos(other),
                Direction.WEST, limits());
        helper.assertValueEqual(fromTheOtherEnd.firstBranchLength(), STRAIGHT_RAILS, "and the other one works too");
        helper.assertValueEqual(fromTheOtherEnd.stop(), NetworkStop.SECOND_DOCK, "stopping at this dock");
        helper.succeed();
    }

    /**
     * A T is an ordinary warehouse (M22, issue #2): the rails split and the whole of them belongs to one warehouse,
     * which is exactly what M21 refused to walk into and reported as "the rails split here".
     * <p>
     * The three-way case needs <b>no new ownership rule</b>, and that is what the second half asserts. At most one
     * aisle per axis passes through any block, so the junction block has one aisle per axis and no rack position of
     * its own, its one free face belongs to exactly one aisle, and a block beside both aisles offers one candidate per
     * aisle — each wanting its own facing of the same block, so a member can satisfy at most one of them.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void networkfollowsatee(GameTestHelper helper) {
        placeDock(helper, DOCK);
        placeStraightLeg(helper);
        placeCornerLeg(helper);
        BlockPos junction = DOCK.offset(STRAIGHT_RAILS, 0, 0);
        helper.setBlock(junction.north(), rail(Direction.Axis.Z));

        RailNetwork network = scan(helper);
        if (!cornersEnabled()) {
            // The off switch reads nothing beside the dock's own line, so a T is one straight aisle, as in 0.5.0 -
            // and that is decided by the bounds the dock really walks with, never by this holder's own generous ones,
            // which deliberately keep seeing the junction so the rest of the test stays a T.
            RailNetwork configured = configuredScan(helper);
            helper.assertValueEqual(configured.branchCount(), 1, "one aisle with the off switch on");
            helper.assertValueEqual(configured.firstBranchLength(), STRAIGHT_RAILS, "the rails in front of the dock");
            helper.assertValueEqual(configured.rails(), STRAIGHT_RAILS, "and not one rail beside its line");
            helper.assertValueEqual(dockAt(helper).aisleLength(), STRAIGHT_RAILS, "which is the dock's own aisle");
            helper.succeed();
            return;
        }
        helper.assertValueEqual(network.branchCount(), 2, "the run and the aisle crossing it");
        helper.assertValueEqual(network.stop(), NetworkStop.END, "the rails simply end: nothing is refused any more");
        helper.assertTrue(network.isComplete(), "and nothing was cut off");
        helper.assertValueEqual(network.rails(), STRAIGHT_RAILS + CORNER_RAILS + 1,
                "every rail belongs to an aisle, and the junction is counted once");
        helper.assertValueEqual(network.geometry().branch(0), new BranchGeometry(0, 0, 0, Heading.EAST, STRAIGHT_RAILS),
                "the aisle at the dock runs straight through the junction and keeps one letter");
        helper.assertValueEqual(network.geometry().branch(1),
                new BranchGeometry(1, STRAIGHT_RAILS, -1, Heading.SOUTH, CORNER_RAILS + 1),
                "the crossing aisle is one aisle on both sides of the run, numbered from the end nearer the dock");
        helper.assertValueEqual(network.geometry().links(),
                List.of(new BranchLink(0, STRAIGHT_RAILS, 1, 1)),
                "and they share exactly the junction block, which has a legal name on both");
        helper.assertValueEqual(dockAt(helper).aisleLength(), STRAIGHT_RAILS,
                "the dock's own aisle is the whole run, not the part before the junction");

        WarehouseLayout layout = layoutOf(helper, network);
        BlockPos junctionBlock = helper.absolutePos(junction);
        helper.assertTrue(layout.candidates(junctionBlock).isEmpty(), "the junction block is no rack position");
        helper.assertTrue(layout.candidates(junctionBlock.above()).isEmpty(),
                "and neither is the mast column above it");
        // Three rails meet at the junction, so it has one free face - and it is served, by the aisle it faces.
        helper.assertValueEqual(layout.candidates(junctionBlock.east()),
                List.of(new RackPosition(1, 1, 0, Side.LEFT)), "its one free face belongs to the crossing aisle");
        // The block beside both aisles: one candidate each, and each wants a different facing of the same block, so
        // exactly zero or one of them can ever be satisfied. The rule M21 built decides a T without a line of change.
        BlockPos besideBoth = helper.absolutePos(DOCK).offset(STRAIGHT_RAILS - 1, 0, -1);
        List<RackPosition> candidates = layout.candidates(besideBoth);
        helper.assertValueEqual(candidates, List.of(new RackPosition(0, STRAIGHT_RAILS - 1, 0, Side.LEFT),
                new RackPosition(1, 0, 0, Side.RIGHT)), "one position per aisle");
        helper.assertValueEqual(awayDirections(layout, candidates), List.of(Direction.NORTH, Direction.WEST),
                "and each of them wants its own facing");

        // The junction really is drawn as one: three rails meet there (the model is cosmetic, the count is not).
        BlockState state = helper.getBlockState(junction);
        helper.assertTrue(state.getValue(WarehouseRailBlock.WEST) && state.getValue(WarehouseRailBlock.NORTH)
                && state.getValue(WarehouseRailBlock.SOUTH), "the junction draws itself as one");
        helper.succeed();
    }

    /**
     * A cross and a ring, the two shapes M21 reported as "the rails split" and "the rails lead back into themselves".
     * Both are ordinary warehouses now: the crossing aisle is <b>one</b> aisle on both sides of the run, and a ring is
     * simply a warehouse whose aisles meet twice.
     */
    @GameTest(template = EMPTY_7X5X7)
    public static void networkfollowsacrossandaring(GameTestHelper helper) {
        placeDock(helper, DOCK);
        placeStraightLeg(helper);
        // A cross at the middle rail: one spur north, one south, so four rails meet at (2, 1).
        BlockPos centre = DOCK.offset(2, 0, 0);
        helper.setBlock(centre.north(), rail(Direction.Axis.Z));
        helper.setBlock(centre.south(), rail(Direction.Axis.Z));

        RailNetwork cross = scan(helper);
        if (!cornersEnabled()) {
            // Again at the configured bounds: with the off switch on, neither spur of the cross is read at all.
            RailNetwork configured = configuredScan(helper);
            helper.assertValueEqual(configured.branchCount(), 1, "one aisle with the off switch on");
            helper.assertValueEqual(configured.firstBranchLength(), STRAIGHT_RAILS, "the rails in front of the dock");
            helper.assertValueEqual(configured.rails(), STRAIGHT_RAILS, "and neither spur of the cross with them");
            helper.succeed();
            return;
        }
        helper.assertValueEqual(cross.branchCount(), 2, "a cross is two aisles, not four");
        helper.assertValueEqual(cross.stop(), NetworkStop.END, "and nothing about it is refused");
        helper.assertValueEqual(cross.geometry().branch(1), new BranchGeometry(1, 2, -1, Heading.SOUTH, 2),
                "the crossing aisle runs through the junction in one piece");
        helper.assertValueEqual(cross.geometry().links(), List.of(new BranchLink(0, 2, 1, 1)),
                "sharing the one block they cross at");

        // Close the ring: rails from the north spur round to the south spur, east of the run.
        helper.setBlock(DOCK.offset(3, 0, -1), rail(Direction.Axis.X));
        helper.setBlock(DOCK.offset(3, 0, 1), rail(Direction.Axis.X));
        RailNetwork ring = scan(helper);
        helper.assertValueEqual(ring.stop(), NetworkStop.END, "a ring is a warehouse, not a refusal");
        helper.assertTrue(ring.isComplete(), "and all of it was taken");
        helper.assertValueEqual(ring.branchCount(), 5,
                "the run, the aisle crossing it, the two sides of the ring and the aisle that closes it");
        helper.assertValueEqual(ring.geometry().links().size(), 6, "whose aisles meet at six junctions");
        WarehouseLayout layout = layoutOf(helper, ring);
        helper.assertTrue(layout.routes().reachable(0, 4), "and every aisle of it is joined to the one at the dock");
        helper.assertTrue(layout.route(0, 0.0, 4, 0.0).isPresent(), "with a real way there");
        helper.succeed();
    }

    /** The rail cap keeps the warehouse valid and says it ran into the limit. */
    @GameTest(template = EMPTY_7X5X7)
    public static void networkstopsatitsrailcap(GameTestHelper helper) {
        placeDock(helper, DOCK);
        placeStraightLeg(helper);

        RailNetwork capped = RailNetworkScan.scan(helper.getLevel(), helper.absolutePos(DOCK), Direction.EAST,
                new RailGraph.Limits(1, 26, MANY_JUNCTIONS, 128, MAST_HEIGHT));
        helper.assertValueEqual(capped.firstBranchLength(), 1, "one rail taken");
        helper.assertValueEqual(capped.stop(), NetworkStop.MAX_RAILS, "and the cap is named");

        RailNetwork shortBranch = RailNetworkScan.scan(helper.getLevel(), helper.absolutePos(DOCK), Direction.EAST,
                new RailGraph.Limits(64, 26, MANY_JUNCTIONS, 2, MAST_HEIGHT));
        helper.assertValueEqual(shortBranch.firstBranchLength(), 2, "a branch is truncated at its far end");
        helper.assertValueEqual(shortBranch.stop(), NetworkStop.MAX_LENGTH, "and that cap is named too");
        helper.succeed();
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    private static void placeDock(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, dockState(Direction.EAST));
    }

    private static BlockState dockState(Direction facing) {
        return WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, facing);
    }

    private static BlockState rail(Direction.Axis axis) {
        return WarehouseRailBlock.along(axis);
    }

    /** Rails at x = 2..4, z = 1: the straight leg, whose last rail is the corner block. */
    private static void placeStraightLeg(GameTestHelper helper) {
        for (int x = 1; x <= STRAIGHT_RAILS; x++)
            helper.setBlock(DOCK.offset(x, 0, 0), rail(Direction.Axis.X));
    }

    /** Rails south of the corner block: the second leg. */
    private static void placeCornerLeg(GameTestHelper helper) {
        for (int z = 1; z <= CORNER_RAILS; z++)
            helper.setBlock(DOCK.offset(STRAIGHT_RAILS, 0, z), rail(Direction.Axis.Z));
    }

    private static RailGraph.Limits limits() {
        return new RailGraph.Limits(64, RackPosition.MAX_BRANCH + 1, MANY_JUNCTIONS, 128, MAST_HEIGHT);
    }

    /**
     * Discovers the network from the dock and refreshes the dock, then checks that the dock itself recorded the same
     * thing: where its warehouse stops is never only this test's opinion.
     * <p>
     * The dock walks its rails inside the bounds the <b>server config</b> gives it, so that is what it is compared
     * against; the network this method returns is taken with the bounds this holder is about, which is what lets the
     * corner case still be a corner while {@code aisle.maxBranches} is 1.
     */
    private static RailNetwork scan(GameTestHelper helper) {
        StackerCraneBlockEntity dock = dockAt(helper);
        dock.refreshGeometry();
        RailNetwork network = RailNetworkScan.scan(helper.getLevel(), helper.absolutePos(DOCK), Direction.EAST,
                limits());
        RailNetwork asConfigured = RailNetworkScan.scan(helper.getLevel(), helper.absolutePos(DOCK), Direction.EAST,
                configuredLimits(dock));
        RailNetwork recorded = dock.discoveredNetwork().orElse(null);
        if (recorded == null) {
            helper.fail("the dock recorded no network", DOCK);
            return network;
        }
        helper.assertValueEqual(recorded.stop(), asConfigured.stop(), "the dock records where its warehouse stops");
        helper.assertValueEqual(recorded.firstBranchLength(), asConfigured.firstBranchLength(),
                "and how long its own aisle is");
        helper.assertValueEqual(recorded.branchCount(), asConfigured.branchCount(),
                "and how many aisles it has");
        return network;
    }

    /**
     * The same warehouse as {@link #scan} discovers, but inside the bounds the <b>server config</b> gives the dock:
     * what a player on this server really gets. This is the one the off-switch arms assert, because
     * {@code aisle.maxBranches = 1} is a statement about the configured warehouse and about nothing else.
     */
    private static RailNetwork configuredScan(GameTestHelper helper) {
        return RailNetworkScan.scan(helper.getLevel(), helper.absolutePos(DOCK), Direction.EAST,
                configuredLimits(dockAt(helper)));
    }

    /**
     * Whether a warehouse may bend at all: {@code aisle.maxBranches = 1} is the switch a server owner sets to keep
     * every warehouse the single straight aisle it was before 0.6, and it is the regression oracle of this milestone.
     */
    private static boolean cornersEnabled() {
        return WareworksConfig.maxBranches() > 1;
    }

    /** The bounds the dock itself walks with: the server config, exactly as {@code refreshGeometry} reads it. */
    private static RailGraph.Limits configuredLimits(StackerCraneBlockEntity dock) {
        return new RailGraph.Limits(WareworksConfig.maxNetworkRails(), WareworksConfig.maxBranches(),
                WareworksConfig.maxJunctions(),
                Math.min(WareworksConfig.maxAisleLength(), AisleGeometry.MAX_LENGTH), dock.mastHeight());
    }

    private static StackerCraneBlockEntity dockAt(GameTestHelper helper) {
        if (helper.getBlockEntity(DOCK) instanceof StackerCraneBlockEntity dock)
            return dock;
        helper.fail("no stacker crane dock", DOCK);
        throw new AssertionError("unreachable");
    }

    private static WarehouseLayout layoutOf(GameTestHelper helper, RailNetwork network) {
        return WarehouseLayout.of(helper.absolutePos(DOCK), Direction.EAST, network.geometry(), Optional.of(LETTER));
    }

    private static BlockPos stopPos(GameTestHelper helper, RailNetwork network) {
        return helper.absolutePos(DOCK).offset(network.stopDx(), 0, network.stopDz());
    }

    /** The direction a storage interface would have to face to own each candidate. */
    private static List<Direction> awayDirections(WarehouseLayout layout, List<RackPosition> candidates) {
        return candidates.stream().map(rack -> layout.branch(rack.branch()).sideDirection(rack.side())).toList();
    }

    /** Uses a wrench on the top face of a test-relative position, like a player who is not sneaking. */
    private static void wrench(GameTestHelper helper, BlockPos pos) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, AllItems.WRENCH.asStack());
        BlockPos absolute = helper.absolutePos(pos);
        Vec3 hit = Vec3.atCenterOf(absolute).add(0.0, 0.5, 0.0);
        helper.useBlock(pos, player, new BlockHitResult(hit, Direction.UP, absolute, false));
    }
}
