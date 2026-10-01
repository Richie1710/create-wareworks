package dev.wareworks.client.ponder.scenes;

import java.util.List;
import java.util.Optional;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.content.controller.WarehouseLayout;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.crane.WarehouseRailBlock;
import dev.wareworks.core.address.BranchGeometry;
import dev.wareworks.core.address.BranchLink;
import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.NetworkGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.address.StorageAddress;
import dev.wareworks.content.station.WarehouseOutputBlock;
import dev.wareworks.content.storage.WarehouseInterfaceBlock;
import dev.wareworks.registry.WareworksBlocks;
import dev.wareworks.util.Headings;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The stage of a Ponder scene whose warehouse <b>bends or splits</b>: a rail network of straight aisles meeting at
 * shared corner and junction blocks ({@code docs/warehouse-system.md} §1, ADR-033, ADR-035), in scene coordinates.
 * <p>
 * Where {@link PonderAisle} computes its positions itself, this stage asks the <b>real</b> mapping: it builds the very
 * {@link WarehouseLayout} a controller would hold for the same rails, so every block the scene places stands exactly
 * where the running game would put it, and the address a caption quotes is the address the game really answers. A
 * corner scene that drifted from the ownership rule it teaches would be worse than no scene at all.
 * <p>
 * Two shapes are built: {@link #CORNER}, the L of M21, and {@link #COMB}, the main run with two side aisles of M22.
 * Both fit the same square base plate of {@value #CORNER_PLATE}.
 * <p>
 * <b>The L</b> ({@link #CORNER}):
 * <pre>
 *   x =         0     1     2  3  4  5  6     7     8
 *   z = 1                               B                 inventory of the corner rack on aisle A
 *   z = 2                               I                 that rack itself, on the corner block's north face
 *   z = 3    ctrl  dock  ═══════════════╗     I     B     aisle A runs east; its last position is the corner block
 *   z = 4              I  I             ║                 rack plane of aisle A, on its right (south) side
 *   z = 5              B  B             ║     I     B     aisle B runs south out of that same block
 *   z = 6                               ║     I     B     rack plane of aisle B, on its left (east) side
 *   z = 7                               ║
 *   z = 8                               ║
 * </pre>
 * <b>Both rack planes lie on the side away from the camera</b> ({@code NetworkScenes}): Ponder shows a block's north
 * and west faces, so a rack north of aisle A or west of aisle B would stand between the viewer and the machine. The one
 * exception is the corner block's own north face, which is the rack this scene exists to show reaching into.
 * The corner block is the <b>last position of aisle A and position 0 of aisle B at the same time</b>, which is what
 * makes its two free faces a rack of each aisle: the north face is laterally beside a straight rail of A, the east face
 * beside one of B, and the interface's facing is the whole rule.
 * <p>
 * <b>The comb</b> ({@link #COMB}), on the same square plate. {@code O} is the collecting port, {@code I} a storage
 * interface and {@code B} the barrel behind it:
 * <pre>
 *   x =    0    1    2    3    4    5    6    7    8
 *   z = 2  ctrl dock ═════╦══════════════╦══════════      the main run, aisle A, through two junctions
 *   z = 3       O         ║    B    I    ║    I           the port; the twin pair beside the run AND aisle C
 *   z = 4                 ║              ║    B
 *   z = 5                 ║              ║
 *   z = 6                 ║    I    B    ║                the rack of aisle B, on its far side
 * </pre>
 * The two interfaces on row 3, at columns 5 and 7, are the point of the comb stage: each of them is laterally beside a
 * <b>straight</b> rail of the main run <i>and</i> of the side aisle at column 6, so both aisles offer it as a storage
 * location and only the way the interface faces decides which one owns it. The one at column 5 faces <b>west</b>, away
 * from that side aisle, and is the side aisle's; the one at column 7 faces <b>south</b>, away from the run, and is the
 * run's. Which of the two carries which is not arbitrary, and the reason is in
 * {@code NetworkScenes#RUN_TWIN_X}: only this way round does each of them show the camera the face its caption is
 * about.
 * <p>
 * Everything is placed with {@code scene.world().setBlock(...)}, so no Wareworks block state lives in the {@code .nbt}
 * ({@link PonderAisle}). A {@code SchematicLevel} runs <b>no neighbour updates at all</b>, so the rails' connection
 * flags — which are what draws a corner as a corner — are written here explicitly, exactly as
 * {@code WarehouseRailBlock#withConnections} would compute them in a world.
 *
 * @param warehouse the mapping of the whole network, with the aisle letters the scene quotes
 * @param plateSize edge length of the square base plate
 */
public record PonderNetwork(WarehouseLayout warehouse, int plateSize) {
    /** Y of everything standing on the base plate, shared with the straight stage. */
    public static final int FLOOR_Y = PonderAisle.FLOOR_Y;
    /** Mast height of a network stage: levels 0 and 1, as on the straight stage. */
    public static final int SCENE_MAST_HEIGHT = PonderAisle.SCENE_MAST_HEIGHT;
    /**
     * Edge length of a network stage's base plate; each schematic is {@value} x 7 x {@value}. The same square nine as
     * the straight aisle stage ({@link PonderAisle#WIDE}), and both shapes fit it exactly: on the L the controller sits
     * on column 0, the far end of aisle B on row 8 and the inventory of the corner rack on aisle B on column 8; on the
     * comb the main run ends on column 8 and the side aisles on row 6.
     */
    public static final int CORNER_PLATE = 9;
    /** Index of the aisle at the dock, which runs east. */
    public static final int FIRST = RackPosition.FIRST_BRANCH;
    /** Index of the second aisle: the one beyond the corner on the L, the first side aisle on the comb. */
    public static final int SECOND = FIRST + 1;
    /** Index of the comb's second side aisle. */
    public static final int THIRD = SECOND + 1;
    /** Rails of each aisle of the L beyond its own position 0. */
    public static final int RAILS_PER_AISLE = 5;
    /** Rails of the comb's main run beyond the dock. */
    public static final int MAIN_RAILS = 7;
    /** Rails of each side aisle of the comb beyond the junction it leaves the main run at. */
    public static final int TOOTH_RAILS = 4;
    /** Position of the main run the comb's first side aisle leaves it at. */
    public static final int FIRST_JUNCTION = 2;
    /** Position of the main run the comb's second side aisle leaves it at. */
    public static final int SECOND_JUNCTION = 5;

    /** Scene X of the dock block; the controller stands one block behind it. */
    private static final int DOCK_X = 1;
    /** Scene Z of the aisle at the dock on the L, which runs the second aisle down to the far edge of the plate. */
    private static final int CORNER_AISLE_Z = 3;
    /**
     * Scene Z of the main run on the comb. One row further north than the L's, because the comb's side aisles are
     * shorter than the L's second aisle and the whole shape would otherwise sit in the southern half of the plate.
     */
    private static final int COMB_AISLE_Z = 2;
    /** NBT key of Create's {@code ScrollValueBehaviour}, which is what the dock's "Mast Height" value box stores. */
    private static final String SCROLL_VALUE = "ScrollValue";

    /**
     * The corner stage's <b>dock-relative</b> shape: aisle A east out of the dock, aisle B south out of A's last
     * position. Independent of the scene grid, so it can be handed to the crane script and to
     * {@code StackerCraneBlockEntity#showClientPose} unchanged.
     */
    public static final NetworkGeometry CORNER = new NetworkGeometry(List.of(
            BranchGeometry.first(Heading.EAST, RAILS_PER_AISLE),
            new BranchGeometry(SECOND, RAILS_PER_AISLE, 0, Heading.SOUTH, RAILS_PER_AISLE)), SCENE_MAST_HEIGHT);

    /**
     * The comb stage's <b>dock-relative</b> shape (M22, issue #2): the main run east out of the dock, and two side
     * aisles running south out of the <b>middle</b> of it, so the run carries on past each junction.
     * <p>
     * The branch order is the one a real discovery produces, which is what lets the scene quote real addresses: the run
     * containing the dock is branch {@value #FIRST}, and the side aisles follow in the order of the position they leave
     * it at ({@code core.warehouse.RailGraph}).
     */
    public static final NetworkGeometry COMB = new NetworkGeometry(List.of(
            BranchGeometry.first(Heading.EAST, MAIN_RAILS),
            new BranchGeometry(SECOND, FIRST_JUNCTION, 0, Heading.SOUTH, TOOTH_RAILS),
            new BranchGeometry(THIRD, SECOND_JUNCTION, 0, Heading.SOUTH, TOOTH_RAILS)), SCENE_MAST_HEIGHT);

    /** The corner stage on the scene grid of {@code util}, with aisle letters A and B. */
    public static PonderNetwork corner(SceneBuildingUtil util) {
        return stage(util, CORNER_AISLE_Z, CORNER, List.of(Optional.of('A'), Optional.of('B')));
    }

    /** The comb stage on the scene grid of {@code util}, with aisle letters A, B and C. */
    public static PonderNetwork comb(SceneBuildingUtil util) {
        return stage(util, COMB_AISLE_Z, COMB, List.of(Optional.of('A'), Optional.of('B'), Optional.of('C')));
    }

    /** One stage: the dock on the scene grid, the shape around it and the letters its aisles carry. */
    private static PonderNetwork stage(SceneBuildingUtil util, int aisleZ, NetworkGeometry network,
            List<Optional<Character>> letters) {
        BlockPos dock = util.grid().at(DOCK_X, FLOOR_Y, aisleZ);
        WarehouseLayout layout = WarehouseLayout.of(dock, Headings.direction(network.firstBranch().heading()), network,
                letters.getFirst()).withBranchLetters(letters);
        return new PonderNetwork(layout, CORNER_PLATE);
    }

    /** The dock-relative shape, which is what the crane script and the block entity need. */
    public NetworkGeometry network() {
        return warehouse.network();
    }

    public BlockPos dock() {
        return warehouse.dock();
    }

    /** The controller behind the dock. */
    public BlockPos controller() {
        return warehouse.dock().relative(warehouse.facing().getOpposite());
    }

    /** The creative motor in the base plate layer that drives the dock from below. */
    public BlockPos motor() {
        return warehouse.dock().below();
    }

    /** Number of aisles this stage is made of. */
    public int aisleCount() {
        return warehouse.branchCount();
    }

    /** Rails of {@code branch} beyond its own position 0. */
    public int length(int branch) {
        return warehouse.network().branch(branch).length();
    }

    /** Position {@code x} of {@code branch}; position 0 of the first aisle is the dock itself. */
    public BlockPos aisle(int branch, int x) {
        return warehouse.aislePos(branch, x);
    }

    /** The one block both aisles own: the last position of the first aisle and position 0 of the second. */
    public BlockPos corner() {
        return warehouse.aislePos(FIRST, RAILS_PER_AISLE);
    }

    /**
     * The block the main run and {@code branch} share: position 0 of that side aisle, and a position of the run in the
     * middle of it. The comb's junctions ({@link #COMB}).
     */
    public BlockPos junction(int branch) {
        return warehouse.aislePos(branch, 0);
    }

    /** The rack position {@code (branch, x, level, side)} in scene coordinates. */
    public BlockPos rack(int branch, int x, int level, Side side) {
        return warehouse.rackPos(new RackPosition(branch, x, level, side));
    }

    /** The inventory behind that rack position, one block further out. */
    public BlockPos inventory(int branch, int x, int level, Side side) {
        RackPosition position = new RackPosition(branch, x, level, side);
        return warehouse.rackPos(position).relative(warehouse.sideDirection(position));
    }

    /** The address the running game gives that rack position — the string a caption may quote. */
    public String address(int branch, int x, int level, Side side) {
        return warehouse.address(new RackPosition(branch, x, level, side)).map(StorageAddress::format)
                .orElseThrow(() -> new IllegalStateException("no address for " + branch + "/" + x));
    }

    /** The direction a storage interface at that rack position must face: away from the aisle it belongs to. */
    public Direction outward(int branch, int x, int level, Side side) {
        return warehouse.sideDirection(new RackPosition(branch, x, level, side));
    }

    /**
     * Checks that the block at the rack position {@code (branch, x, level, side)} really is offered as a storage
     * location by {@code otherBranch} as well — a block laterally beside a straight rail of <b>both</b> aisles, which
     * only the way its interface faces tells apart ({@code WarehouseLayout#candidates}).
     * <p>
     * A scene that teaches that rule must not assume it: if the stage ever moves so that one of its twin blocks stops
     * being a candidate of both aisles, the caption becomes a lie. This turns that into a storyboard that fails to
     * compile, which the {@code ponder} visual scenario runs on every build.
     *
     * @return the rack position, so a call can be used where the position is wanted
     * @throws IllegalStateException if the block is a candidate of only one aisle, or not of {@code otherBranch}
     */
    public BlockPos requireTwinOf(int branch, int x, int level, Side side, int otherBranch) {
        RackPosition own = new RackPosition(branch, x, level, side);
        BlockPos pos = warehouse.rackPos(own);
        List<RackPosition> candidates = warehouse.candidates(pos);
        boolean mine = candidates.stream().anyMatch(candidate -> candidate.branch() == branch
                && candidate.x() == x && candidate.side() == side);
        boolean theirs = candidates.stream().anyMatch(candidate -> candidate.branch() == otherBranch);
        if (!mine || !theirs)
            throw new IllegalStateException(pos + " is not a rack of aisle " + branch + " and aisle " + otherBranch
                    + " at once: " + candidates);
        return pos;
    }

    /**
     * Every rail of one aisle, as a selection: positions 1..{@value #RAILS_PER_AISLE} of the aisle at the dock (whose
     * position 0 is the dock itself and not a rail), and 0..{@value #RAILS_PER_AISLE} of every further aisle — so the
     * shared corner block, which is the last position of one aisle and position 0 of the next, is in <b>both</b>.
     * That is the truth an outline of "aisle B" has to show.
     */
    public Selection rails(SceneBuildingUtil util, int branch) {
        BlockPos from = aisle(branch, branch == FIRST ? 1 : 0);
        BlockPos to = aisle(branch, length(branch));
        return util.select().fromTo(from, to);
    }

    /**
     * The rails an aisle brings that no earlier aisle already has: for the aisle at the dock everything up to but
     * <b>not including</b> the corner it shares with the next one, for a later aisle everything from that corner on.
     * <p>
     * This is what a chain stage fades in, so that the shared block arrives together with the rails that make it a
     * corner rather than standing there as a piece of rail curving into thin air.
     * <p>
     * Only defined while every block an aisle shares with a <b>later</b> one lies at its own far end, which is what a
     * chain is ({@link #CORNER}). On a stage whose aisles leave the run in the middle ({@link #COMB}) the answer would
     * not be one contiguous run of rails, so such a stage uses {@link #branchRails} instead and this throws rather than
     * quietly fading in a shape with a hole in it.
     *
     * @throws IllegalStateException if a later aisle shares a block that is not this aisle's far end
     */
    public Selection newRails(SceneBuildingUtil util, int branch) {
        int length = length(branch);
        int to = length;
        for (BranchLink link : warehouse.network().links()) {
            int shared = link.positionOn(branch);
            if (shared < 0 || link.other(branch) < branch)
                continue;
            if (shared != length)
                throw new IllegalStateException("aisle " + branch + " shares its position " + shared
                        + " with the later aisle " + link.other(branch) + ", which is not its far end " + length);
            to = length - 1;
        }
        return util.select().fromTo(aisle(branch, branch == FIRST ? 1 : 0), aisle(branch, to));
    }

    /**
     * The rails of one aisle beyond its own position 0 — what a side aisle adds to a comb that the junction it leaves
     * the run at did not already carry ({@link #COMB}).
     * <p>
     * Always one contiguous run, whatever the shape, because position 0 of a later aisle is the only position it can
     * share with an earlier one: a discovered aisle's origin is its end nearer the dock.
     */
    public Selection branchRails(SceneBuildingUtil util, int branch) {
        return util.select().fromTo(aisle(branch, 1), aisle(branch, length(branch)));
    }

    // --- placement (all while the positions are still hidden) -------------------------------------------------------

    /** Controller behind the dock, the dock itself, the creative motor below it and the rails of both aisles. */
    public void placeNetwork(CreateSceneBuilder scene, SceneBuildingUtil util) {
        scene.world().setBlock(controller(), WareworksBlocks.WAREHOUSE_CONTROLLER.getDefaultState()
                .setValue(WarehouseControllerBlock.FACING, warehouse.facing()), false);
        scene.world().setBlock(dock(), WareworksBlocks.STACKER_CRANE.getDefaultState()
                .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, warehouse.facing()), false);
        // Must happen before any pose is scripted: this round-trips the block entity's NBT, which would undo a pose.
        scene.world().modifyBlockEntityNBT(util.select().position(dock()), StackerCraneBlockEntity.class,
                nbt -> nbt.putInt(SCROLL_VALUE, SCENE_MAST_HEIGHT));
        scene.world().setBlock(motor(), AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, Direction.UP), false);
        for (int branch = FIRST; branch < warehouse.branchCount(); branch++)
            placeRails(scene, branch);
    }

    /**
     * The rails of one aisle, each with the connection flags the world would give it. Position 0 of the first aisle is
     * the dock and is never a rail; position 0 of every further aisle is the corner block it shares with the aisle
     * before it, which is a rail and is placed with the earlier aisle.
     */
    private void placeRails(CreateSceneBuilder scene, int branch) {
        for (int x = 1; x <= length(branch); x++)
            scene.world().setBlock(aisle(branch, x), railAt(branch, x), false);
    }

    /**
     * The state of the rail at position {@code x} of {@code branch}: its cosmetic axis is the way its own aisle runs,
     * and a connection flag is set towards every horizontal neighbour that offers one — another rail, or the dock
     * towards its own facing ({@code WarehouseRailBlock#connectsTowards}). The corner block therefore carries one flag
     * per aisle and draws itself as a corner.
     */
    private BlockState railAt(int branch, int x) {
        BlockPos pos = aisle(branch, x);
        BlockState state = WarehouseRailBlock.along(warehouse.branch(branch).facing().getAxis());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbour = pos.relative(direction);
            boolean connects = isAisleBlock(neighbour)
                    && (!neighbour.equals(dock()) || warehouse.facing() == direction.getOpposite());
            state = state.setValue(WarehouseRailBlock.connection(direction), connects);
        }
        return state;
    }

    /** Whether {@code pos} carries a rail or the dock of this network, i.e. a block the crane drives on. */
    private boolean isAisleBlock(BlockPos pos) {
        for (int branch = FIRST; branch < warehouse.branchCount(); branch++) {
            for (int x = 0; x <= length(branch); x++) {
                if (aisle(branch, x).equals(pos))
                    return true;
            }
        }
        return false;
    }

    /**
     * Closes the <b>far end</b> rail of the last aisle with a wrench: that rail offers no connection any more, and the
     * rail before it loses the flag towards it, which is what a world does through the neighbour update a
     * {@code SchematicLevel} never runs.
     * <p>
     * Deliberately restricted to that one rail. A rail in the middle of a network has neighbours on both sides and, at
     * a corner, on another branch, and re-deriving all of their flags here would be a second copy of
     * {@code WarehouseRailBlock#withConnections} living in a Ponder helper. The far end is the rail a scene wants
     * anyway: closing it is the beat a player can repeat on their own build.
     *
     * @throws IllegalArgumentException if {@code branch} is not the last aisle
     */
    public void closeRail(CreateSceneBuilder scene, int branch) {
        if (branch != warehouse.branchCount() - 1 || branch == FIRST)
            throw new IllegalArgumentException("only the far end of the last aisle may be closed: " + branch);
        int length = length(branch);
        Direction outwards = warehouse.branch(branch).facing();
        scene.world().setBlock(aisle(branch, length), WarehouseRailBlock.along(outwards.getAxis())
                .setValue(WarehouseRailBlock.CLOSED, true), false);
        scene.world().setBlock(aisle(branch, length - 1), railAt(branch, length - 1)
                .setValue(WarehouseRailBlock.connection(outwards), false), false);
    }

    /** A storage location: a warehouse interface facing away from its aisle with a barrel behind it. */
    public void placeStorage(CreateSceneBuilder scene, int branch, int x, int level, Side side) {
        scene.world().setBlock(inventory(branch, x, level, side), Blocks.BARREL.defaultBlockState(), false);
        scene.world().setBlock(rack(branch, x, level, side), WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                .setValue(WarehouseInterfaceBlock.FACING, outward(branch, x, level, side)), false);
    }

    /**
     * An output station, i.e. a warehouse port that requests: its opening faces the aisle it belongs to, which is the
     * opposite of the way a storage interface on the same side faces ({@code PonderAisle#placeOutput}).
     * <p>
     * A requesting port is what an unconfigured block entity already is, so no block entity data has to be written.
     */
    public void placeOutput(CreateSceneBuilder scene, int branch, int x, int level, Side side) {
        scene.world().setBlock(rack(branch, x, level, side), WareworksBlocks.WAREHOUSE_OUTPUT.getDefaultState()
                .setValue(WarehouseOutputBlock.FACING, outward(branch, x, level, side).getOpposite()), false);
    }
}
