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
import dev.wareworks.core.address.Heading;
import dev.wareworks.core.address.NetworkGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;
import dev.wareworks.core.address.StorageAddress;
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
 * The stage of a Ponder scene whose warehouse <b>bends</b>: a rail network of two straight aisles meeting at a shared
 * corner block ({@code docs/warehouse-system.md} §1, ADR-033), in scene coordinates.
 * <p>
 * Where {@link PonderAisle} computes its positions itself, this stage asks the <b>real</b> mapping: it builds the very
 * {@link WarehouseLayout} a controller would hold for the same rails, so every block the scene places stands exactly
 * where the running game would put it, and the address a caption quotes is the address the game really answers. A
 * corner scene that drifted from the ownership rule it teaches would be worse than no scene at all.
 * <p>
 * <b>The shape</b> ({@link #CORNER}), on a square base plate of {@value #CORNER_PLATE}:
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
    /** Mast height of the corner stage: levels 0 and 1, as on the straight stage. */
    public static final int SCENE_MAST_HEIGHT = PonderAisle.SCENE_MAST_HEIGHT;
    /**
     * Edge length of the corner stage's base plate; the schematic is {@value} x 7 x {@value}. The same square nine as
     * the straight aisle stage ({@link PonderAisle#WIDE}), and the L fits it exactly: the controller sits on column 0,
     * the far end of aisle B on row 8 and the inventory of the corner rack on aisle B on column 8.
     */
    public static final int CORNER_PLATE = 9;
    /** Index of the aisle at the dock, which runs east. */
    public static final int FIRST = RackPosition.FIRST_BRANCH;
    /** Index of the aisle beyond the corner, which runs south. */
    public static final int SECOND = FIRST + 1;
    /** Rails of each aisle beyond its own position 0. */
    public static final int RAILS_PER_AISLE = 5;

    /** Scene X of the dock block; the controller stands one block behind it. */
    private static final int DOCK_X = 1;
    /** Scene Z of the aisle at the dock. */
    private static final int AISLE_Z = 3;
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

    /** The corner stage on the scene grid of {@code util}, with aisle letters A and B. */
    public static PonderNetwork corner(SceneBuildingUtil util) {
        BlockPos dock = util.grid().at(DOCK_X, FLOOR_Y, AISLE_Z);
        WarehouseLayout layout = WarehouseLayout.of(dock, Headings.direction(CORNER.firstBranch().heading()), CORNER,
                        Optional.of('A'))
                .withBranchLetters(List.of(Optional.of('A'), Optional.of('B')));
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

    /** Position {@code x} of {@code branch}; position 0 of the first aisle is the dock itself. */
    public BlockPos aisle(int branch, int x) {
        return warehouse.aislePos(branch, x);
    }

    /** The one block both aisles own: the last position of the first aisle and position 0 of the second. */
    public BlockPos corner() {
        return warehouse.aislePos(FIRST, RAILS_PER_AISLE);
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
     * Every rail of one aisle, as a selection: positions 1..{@value #RAILS_PER_AISLE} of the aisle at the dock (whose
     * position 0 is the dock itself and not a rail), and 0..{@value #RAILS_PER_AISLE} of every further aisle — so the
     * shared corner block, which is the last position of one aisle and position 0 of the next, is in <b>both</b>.
     * That is the truth an outline of "aisle B" has to show.
     */
    public Selection rails(SceneBuildingUtil util, int branch) {
        BlockPos from = aisle(branch, branch == FIRST ? 1 : 0);
        BlockPos to = aisle(branch, RAILS_PER_AISLE);
        return util.select().fromTo(from, to);
    }

    /**
     * The rails an aisle brings that no earlier aisle already has: for the aisle at the dock everything up to but
     * <b>not including</b> the corner it shares with the next one, for a later aisle everything from that corner on.
     * <p>
     * This is what a scene fades in, so that the shared block arrives together with the rails that make it a corner
     * rather than standing there as a piece of rail curving into thin air.
     */
    public Selection newRails(SceneBuildingUtil util, int branch) {
        boolean shared = branch + 1 < warehouse.branchCount();
        BlockPos from = aisle(branch, branch == FIRST ? 1 : 0);
        BlockPos to = aisle(branch, shared ? RAILS_PER_AISLE - 1 : RAILS_PER_AISLE);
        return util.select().fromTo(from, to);
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
        for (int x = 1; x <= RAILS_PER_AISLE; x++)
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
            for (int x = 0; x <= RAILS_PER_AISLE; x++) {
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
        Direction outwards = warehouse.branch(branch).facing();
        scene.world().setBlock(aisle(branch, RAILS_PER_AISLE), WarehouseRailBlock.along(outwards.getAxis())
                .setValue(WarehouseRailBlock.CLOSED, true), false);
        scene.world().setBlock(aisle(branch, RAILS_PER_AISLE - 1), railAt(branch, RAILS_PER_AISLE - 1)
                .setValue(WarehouseRailBlock.connection(outwards), false), false);
    }

    /** A storage location: a warehouse interface facing away from its aisle with a barrel behind it. */
    public void placeStorage(CreateSceneBuilder scene, int branch, int x, int level, Side side) {
        scene.world().setBlock(inventory(branch, x, level, side), Blocks.BARREL.defaultBlockState(), false);
        scene.world().setBlock(rack(branch, x, level, side), WareworksBlocks.WAREHOUSE_INTERFACE.getDefaultState()
                .setValue(WarehouseInterfaceBlock.FACING, outward(branch, x, level, side)), false);
    }
}
