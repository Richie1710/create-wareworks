package dev.wareworks.content.crane;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;

import dev.wareworks.content.controller.WarehouseControllerBlock;
import dev.wareworks.core.address.Heading;
import dev.wareworks.core.warehouse.RailGraph;
import dev.wareworks.core.warehouse.RailNetwork;
import dev.wareworks.util.Headings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Discovery of the warehouse rail network in front of a stacker crane dock ({@code docs/warehouse-system.md} §1, §4,
 * ADR-033). Owned by the dock, exactly as the rail count was before M21.
 * <p>
 * This class is only the world half: it turns block states into {@link RailGraph.Cell}s. Which blocks connect, where
 * the walk stops and how the result is cut into branches is pure integer maths in {@link RailGraph}, so all of it is
 * JUnit-tested without a {@code Level}.
 * <p>
 * <b>It never loads a chunk.</b> A position in an unloaded chunk is a wall and marks the scan partial, which is what
 * lets the dock keep the last known network whole instead of letting a chunk boundary renumber a warehouse. Nothing is
 * searched for: rails do not find their dock, the dock finds its rails, and every position it reads is one step from a
 * position it already took. Each position is read <b>at most once</b> per scan, so the cost is bounded by the number of
 * aisle blocks plus their neighbours.
 * <p>
 * <b>Other docks (issue #14, ADR-075).</b> A dock the walk meets is told apart by the block <b>behind</b> it: a loaded
 * warehouse controller facing the same way makes it the dock of another warehouse ({@link RailGraph.Cell#OTHER_DOCK}, a
 * wall as it always was), anything else a dock without a controller of its own ({@link RailGraph.Cell#FREE_DOCK}),
 * which may join this warehouse as another crane. A block behind it that is not loaded makes the dock
 * {@link RailGraph.Cell#UNLOADED}: the scan stays partial rather than guessing. That is one more block read per dock
 * the walk touches, and none at all with {@code aisle.maxCranesPerNetwork = 1}, where every other dock is the wall it
 * was up to 0.17.1.
 */
public final class RailNetworkScan {
    private RailNetworkScan() {
    }

    /**
     * Walks the network from {@code dock} and returns what it found, always a valid network.
     *
     * @param level  the dock's level; never loaded by this call
     * @param dock   the dock block, i.e. position 0 of the first branch
     * @param facing the dock's facing, i.e. the first branch's heading
     * @param limits the configured bounds ({@code aisle.maxNetworkRails}, {@code aisle.maxBranches},
     *               {@code aisle.maxAisleLength}, the crane's mast height and {@code aisle.maxCranesPerNetwork})
     */
    public static RailNetwork scan(Level level, BlockPos dock, Direction facing, RailGraph.Limits limits) {
        return scan(level, dock, facing, limits, Set.of(), 0);
    }

    /**
     * The same, for the dock of a warehouse whose controller already lists the docks {@code listed} as its cranes
     * (issue #14): those keep their places against any dock the walk meets for the first time
     * ({@link RailGraph.CellProbe#listedDock}), and {@code reserved} places of the cap are taken by stranded cranes
     * ({@link RailGraph.CellProbe#reservedPlaces}).
     */
    public static RailNetwork scan(Level level, BlockPos dock, Direction facing, RailGraph.Limits limits,
            Set<BlockPos> listed, int reserved) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(dock, "dock");
        Objects.requireNonNull(listed, "listed");
        if (!facing.getAxis().isHorizontal())
            throw new IllegalArgumentException("a dock faces horizontally: " + facing);
        return RailGraph.scan(new LevelProbe(level, dock, limits.maxCranes() > RailGraph.Limits.MIN_CRANES, listed,
                reserved), Headings.of(facing), limits);
    }

    /**
     * Whether the dock at {@code dock} facing {@code facing} has a warehouse controller of its own: a loaded controller
     * directly behind it that faces it. Never loads a chunk; an unloaded block behind it answers no.
     */
    public static boolean hasOwnController(Level level, BlockPos dock, Direction facing) {
        BlockPos behind = dock.relative(facing.getOpposite());
        if (!level.isLoaded(behind))
            return false;
        BlockState state = level.getBlockState(behind);
        return state.getBlock() instanceof WarehouseControllerBlock
                && state.getOptionalValue(WarehouseControllerBlock.FACING).orElse(null) == facing;
    }

    /** Reads the world around one dock, remembering every position it has already read. */
    private static final class LevelProbe implements RailGraph.CellProbe {
        private final Level level;
        private final BlockPos dock;
        /** Whether another dock may join: only then is the block behind a dock worth a read (issue #14). */
        private final boolean membersAllowed;
        /** The docks the warehouse already lists as its cranes, which keep their places. */
        private final Set<BlockPos> listed;
        /** Places of the cap that stranded cranes take. */
        private final int reserved;
        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        private final Map<Long, RailGraph.Cell> read = new HashMap<>();

        LevelProbe(Level level, BlockPos dock, boolean membersAllowed, Set<BlockPos> listed, int reserved) {
            this.level = level;
            this.dock = dock;
            this.membersAllowed = membersAllowed;
            this.listed = listed;
            this.reserved = reserved;
        }

        @Override
        public boolean listedDock(int dx, int dz) {
            return !listed.isEmpty() && listed.contains(dock.offset(dx, 0, dz));
        }

        @Override
        public int reservedPlaces() {
            return reserved;
        }

        @Override
        public RailGraph.Cell at(int dx, int dz) {
            return read.computeIfAbsent(RailGraph.cell(dx, dz), key -> lookUp(dx, dz));
        }

        @Override
        public Heading dockFacing(int dx, int dz) {
            cursor.set(dock.getX() + dx, dock.getY(), dock.getZ() + dz);
            if (!level.isLoaded(cursor))
                return null;
            BlockState state = level.getBlockState(cursor);
            if (!(state.getBlock() instanceof StackerCraneBlock))
                return null;
            return state.getOptionalValue(HorizontalKineticBlock.HORIZONTAL_FACING).map(Headings::of).orElse(null);
        }

        private RailGraph.Cell lookUp(int dx, int dz) {
            if (dx == 0 && dz == 0)
                return RailGraph.Cell.DOCK;
            cursor.set(dock.getX() + dx, dock.getY(), dock.getZ() + dz);
            if (!level.isLoaded(cursor))
                return RailGraph.Cell.UNLOADED;
            BlockState state = level.getBlockState(cursor);
            if (WarehouseRailBlock.isRail(state))
                return WarehouseRailBlock.isOpenRail(state) ? RailGraph.Cell.RAIL : RailGraph.Cell.CLOSED_RAIL;
            if (!(state.getBlock() instanceof StackerCraneBlock))
                return RailGraph.Cell.NONE;
            // Another dock is a wall, so two warehouses whose rails meet keep working and neither swallows the other.
            // With one crane per warehouse that is the whole answer, and the block behind it is never read.
            if (!membersAllowed)
                return RailGraph.Cell.OTHER_DOCK;
            Direction facing = state.getOptionalValue(HorizontalKineticBlock.HORIZONTAL_FACING).orElse(Direction.NORTH);
            BlockPos at = cursor.immutable();
            BlockPos behind = at.relative(facing.getOpposite());
            if (!level.isLoaded(behind))
                return RailGraph.Cell.UNLOADED; // partial rather than guessed: it may be another warehouse's dock
            return hasOwnController(level, at, facing) ? RailGraph.Cell.OTHER_DOCK : RailGraph.Cell.FREE_DOCK;
        }
    }
}
