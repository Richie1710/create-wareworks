package dev.wareworks.content.station;

import dev.wareworks.content.controller.AisleAssignment;
import dev.wareworks.util.CraneTrafficLang;
import net.minecraft.nbt.CompoundTag;

/**
 * What the goggle tooltip of a warehouse home point shows, as synced to clients ({@code docs/stacker-crane.md} §4.7,
 * M21).
 * <p>
 * Where the block stands in its warehouse and what the warehouse does with it; for a warehouse with several cranes
 * also which crane waits here (issue #14, ADR-075 §8.4). All of it is derived — the controller decides it — so nothing
 * here is ever read back as truth, and reading never throws: unknown data becomes {@link #NONE}.
 *
 * @param assignment aisle address, misaligned, or not part of an aisle
 * @param status     what the warehouse does with this home point
 * @param crane      the crane that waits here, in a warehouse with several cranes; 0 otherwise. Left out of the
 *                   synced tag while 0, which is every home point of a warehouse with one crane
 * @param several    whether the warehouse has several cranes, which changes what {@link HomePointStatus#SECOND} means:
 *                   not "a warehouse has one home" but "more home points than cranes". Left out while false
 */
public record HomePointGoggleSummary(AisleAssignment assignment, HomePointStatus status, int crane,
                                     boolean several) {
    public static final HomePointGoggleSummary NONE =
            new HomePointGoggleSummary(AisleAssignment.NONE, HomePointStatus.NO_WAREHOUSE, 0, false);

    private static final String ASSIGNMENT = "Assignment";
    private static final String STATUS = "Status";
    private static final String CRANE = "Crane";
    private static final String SEVERAL = "Several";

    public HomePointGoggleSummary {
        if (assignment == null)
            assignment = AisleAssignment.NONE;
        if (status == null)
            status = HomePointStatus.NO_WAREHOUSE;
        crane = several && status == HomePointStatus.SERVING ? Math.max(0, crane) : 0;
    }

    /** The summary of a home point of a warehouse with one crane. */
    public HomePointGoggleSummary(AisleAssignment assignment, HomePointStatus status) {
        this(assignment, status, 0, false);
    }

    /** This summary with another status, for a warehouse with one crane. */
    public HomePointGoggleSummary withStatus(HomePointStatus newStatus) {
        return new HomePointGoggleSummary(assignment, newStatus);
    }

    /** This summary with another status, the crane waiting here and whether the warehouse has several (issue #14). */
    public HomePointGoggleSummary withStatus(HomePointStatus newStatus, int newCrane, boolean newSeveral) {
        return new HomePointGoggleSummary(assignment, newStatus, newCrane, newSeveral);
    }

    /**
     * The relative lang key of the status sentence: for a warehouse with several cranes "Crane 2 waits here" and "more
     * home points than cranes", otherwise the status's own sentence.
     */
    public String statusKey() {
        if (several && status == HomePointStatus.SERVING && crane > 0)
            return CraneTrafficLang.GOGGLES_HOME_POINT_SERVING_CRANE;
        if (several && status == HomePointStatus.SECOND)
            return CraneTrafficLang.GOGGLES_HOME_POINT_SECOND_CRANES;
        return status.langKey();
    }

    /** Writes this summary into {@code tag}. Never throws. */
    public void write(CompoundTag tag) {
        CompoundTag assignmentTag = new CompoundTag();
        assignment.write(assignmentTag);
        tag.put(ASSIGNMENT, assignmentTag);
        tag.putString(STATUS, status.name());
        if (crane > 0)
            tag.putInt(CRANE, crane);
        if (several)
            tag.putBoolean(SEVERAL, true);
    }

    /** Reads a summary written by {@link #write}. Never throws; unknown data reads as {@link #NONE}'s values. */
    public static HomePointGoggleSummary read(CompoundTag tag) {
        return new HomePointGoggleSummary(AisleAssignment.read(tag.getCompound(ASSIGNMENT)),
                HomePointStatus.byName(tag.getString(STATUS)).orElse(HomePointStatus.NO_WAREHOUSE),
                tag.getInt(CRANE), tag.getBoolean(SEVERAL));
    }
}
