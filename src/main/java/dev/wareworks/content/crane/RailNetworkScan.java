package dev.wareworks.content.crane;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

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
     *               {@code aisle.maxAisleLength} and the crane's mast height)
     */
    public static RailNetwork scan(Level level, BlockPos dock, Direction facing, RailGraph.Limits limits) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(dock, "dock");
        if (!facing.getAxis().isHorizontal())
            throw new IllegalArgumentException("a dock faces horizontally: " + facing);
        return RailGraph.scan(new LevelProbe(level, dock), Headings.of(facing), limits);
    }

    /** Reads the world around one dock, remembering every position it has already read. */
    private static final class LevelProbe implements RailGraph.CellProbe {
        private final Level level;
        private final BlockPos dock;
        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        private final Map<Long, RailGraph.Cell> read = new HashMap<>();

        LevelProbe(Level level, BlockPos dock) {
            this.level = level;
            this.dock = dock;
        }

        @Override
        public RailGraph.Cell at(int dx, int dz) {
            return read.computeIfAbsent(RailGraph.cell(dx, dz), key -> lookUp(dx, dz));
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
            // Another dock is a wall, so two warehouses whose rails meet keep working and neither swallows the other.
            return state.getBlock() instanceof StackerCraneBlock ? RailGraph.Cell.OTHER_DOCK : RailGraph.Cell.NONE;
        }
    }
}
