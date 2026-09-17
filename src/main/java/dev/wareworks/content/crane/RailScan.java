package dev.wareworks.content.crane;

import dev.wareworks.core.address.AisleGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/**
 * Result of counting the warehouse rails in front of a stacker crane dock ({@code docs/warehouse-system.md} §1, §4).
 *
 * @param rails                consecutive rails at {@code dock + facing·1 … dock + facing·rails} whose axis equals the
 *                             facing axis, capped at the scan limit
 * @param reachedUnloadedChunk whether the scan stopped at a position whose chunk is not loaded (the rails beyond are
 *                             unknown, see {@link AisleGeometry#scannedLength})
 */
public record RailScan(int rails, boolean reachedUnloadedChunk) {
    /**
     * Counts rails from the dock along {@code facing}: at most {@code maxLength} block state reads, never loading a
     * chunk. The scan stops at the first position that is not a rail along the facing axis (gap, other block, rail
     * across the aisle), at an unloaded position, or at the cap.
     */
    public static RailScan scan(Level level, BlockPos dock, Direction facing, int maxLength) {
        int limit = Math.clamp(maxLength, 0, AisleGeometry.MAX_LENGTH);
        Direction.Axis axis = facing.getAxis();
        BlockPos.MutableBlockPos cursor = dock.mutable();
        int rails = 0;
        while (rails < limit) {
            cursor.move(facing);
            if (!level.isLoaded(cursor))
                return new RailScan(rails, true);
            if (!WarehouseRailBlock.isRailAlong(level.getBlockState(cursor), axis))
                break;
            rails++;
        }
        return new RailScan(rails, false);
    }

    /** The aisle length this scan implies, given the last known length ({@link AisleGeometry#scannedLength}). */
    public int resolveLength(int previousLength, int maxLength) {
        return AisleGeometry.scannedLength(rails, reachedUnloadedChunk, Math.max(0, previousLength),
                Math.clamp(maxLength, 0, AisleGeometry.MAX_LENGTH));
    }
}
