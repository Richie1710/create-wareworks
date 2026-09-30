package dev.wareworks.content.station;

import dev.wareworks.content.controller.AisleAssignment;
import net.minecraft.nbt.CompoundTag;

/**
 * What the goggle tooltip of a warehouse home point shows, as synced to clients ({@code docs/stacker-crane.md} §4.7,
 * M21).
 * <p>
 * Two values: where the block stands in its warehouse and what the warehouse does with it. Both are derived — the
 * controller decides them — so nothing here is ever read back as truth, and reading never throws: unknown data becomes
 * {@link #NONE}.
 *
 * @param assignment aisle address, misaligned, or not part of an aisle
 * @param status     what the warehouse does with this home point
 */
public record HomePointGoggleSummary(AisleAssignment assignment, HomePointStatus status) {
    public static final HomePointGoggleSummary NONE =
            new HomePointGoggleSummary(AisleAssignment.NONE, HomePointStatus.NO_WAREHOUSE);

    private static final String ASSIGNMENT = "Assignment";
    private static final String STATUS = "Status";

    public HomePointGoggleSummary {
        if (assignment == null)
            assignment = AisleAssignment.NONE;
        if (status == null)
            status = HomePointStatus.NO_WAREHOUSE;
    }

    /** This summary with another status. */
    public HomePointGoggleSummary withStatus(HomePointStatus newStatus) {
        return new HomePointGoggleSummary(assignment, newStatus);
    }

    /** Writes this summary into {@code tag}. Never throws. */
    public void write(CompoundTag tag) {
        CompoundTag assignmentTag = new CompoundTag();
        assignment.write(assignmentTag);
        tag.put(ASSIGNMENT, assignmentTag);
        tag.putString(STATUS, status.name());
    }

    /** Reads a summary written by {@link #write}. Never throws; unknown data reads as {@link #NONE}'s values. */
    public static HomePointGoggleSummary read(CompoundTag tag) {
        return new HomePointGoggleSummary(AisleAssignment.read(tag.getCompound(ASSIGNMENT)),
                HomePointStatus.byName(tag.getString(STATUS)).orElse(HomePointStatus.NO_WAREHOUSE));
    }
}
