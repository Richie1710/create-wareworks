package dev.wareworks.content.station;

import dev.wareworks.content.controller.AisleAssignment;
import net.minecraft.nbt.CompoundTag;

/**
 * What the goggle tooltip of a Warehouse Optimizer shows, as synced to clients (issue #46, ADR-079).
 * <p>
 * <b>Numbers only.</b> A block entity's update tag is part of every chunk packet, so the listed items never travel here
 * — only how many there are and how far the optimizer's order has got. The items live in the optimizer's screen, which
 * is a menu payload to the one player who opened it ({@code network.OptimizerScreenPayload}).
 * <p>
 * Records compare by value, so the server syncs only visible changes; negative numbers are clamped to 0 and reading
 * never throws.
 *
 * @param assignment aisle address, misaligned or not part of an aisle
 * @param items      rows of this optimizer that list an item
 * @param linked     whether a loaded controller serves this optimizer at all
 * @param running    whether its order is running
 * @param moved      how many items its order has put down so far
 * @param tripsLeft  how many trips its order may still cost
 */
public record OptimizerGoggleSummary(AisleAssignment assignment, int items, boolean linked, boolean running,
                                     long moved, int tripsLeft) {
    public static final OptimizerGoggleSummary NONE =
            new OptimizerGoggleSummary(AisleAssignment.NONE, 0, false, false, 0L, 0);

    private static final String ASSIGNMENT = "Assignment";
    private static final String ITEMS = "Items";
    private static final String LINKED = "Linked";
    private static final String RUNNING = "Running";
    private static final String MOVED = "Moved";
    private static final String TRIPS_LEFT = "TripsLeft";

    public OptimizerGoggleSummary {
        if (assignment == null)
            assignment = AisleAssignment.NONE;
        items = Math.max(0, items);
        moved = Math.max(0L, moved);
        tripsLeft = Math.max(0, tripsLeft);
    }

    /** Writes this summary into {@code tag}. Never throws. */
    public void write(CompoundTag tag) {
        CompoundTag assignmentTag = new CompoundTag();
        assignment.write(assignmentTag);
        tag.put(ASSIGNMENT, assignmentTag);
        tag.putInt(ITEMS, items);
        tag.putBoolean(LINKED, linked);
        tag.putBoolean(RUNNING, running);
        tag.putLong(MOVED, moved);
        tag.putInt(TRIPS_LEFT, tripsLeft);
    }

    /** Reads a summary written by {@link #write}. Never throws; missing or invalid data reads as empty values. */
    public static OptimizerGoggleSummary read(CompoundTag tag) {
        return new OptimizerGoggleSummary(AisleAssignment.read(tag.getCompound(ASSIGNMENT)), tag.getInt(ITEMS),
                tag.getBoolean(LINKED), tag.getBoolean(RUNNING), tag.getLong(MOVED), tag.getInt(TRIPS_LEFT));
    }
}
