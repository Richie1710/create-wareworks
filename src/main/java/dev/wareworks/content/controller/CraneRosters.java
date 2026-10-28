package dev.wareworks.content.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import dev.wareworks.content.crane.CraneJobSummary;
import dev.wareworks.content.crane.CranePauseReason;
import dev.wareworks.content.crane.CraneTrafficInfo;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.crane.RemainingTrip;
import dev.wareworks.core.job.TransportJob;

/**
 * Builds the {@link CraneRoster} of a warehouse from its cranes (server, issue #14, ADR-075 §15): what each crane is
 * doing in a word, read from state the crane already keeps — its job, its pause reason, what its last traffic tick
 * said ({@link StackerCraneBlockEntity#trafficInfo()}) and whether the controller lists it as stranded.
 * <p>
 * Never a scan: one block entity lookup per crane. The times are worked out only for the controller's goggles, which
 * ask at most once a second while somebody looks; the terminal's counts never need them.
 */
public final class CraneRosters {
    private CraneRosters() {
    }

    /**
     * Every crane of {@code controller}'s warehouse in crane order, with what it is doing.
     *
     * @param withTimes whether to work out how long each job still takes (goggles) or not (counts)
     */
    public static List<CraneRoster.Entry> entries(WarehouseControllerBlockEntity controller, boolean withTimes) {
        List<CraneMember> members = controller.craneMembers();
        List<CraneRoster.Entry> entries = new ArrayList<>(members.size());
        for (CraneMember member : members)
            entries.add(entry(member, controller.craneOf(member), withTimes));
        return entries;
    }

    /** What one crane is doing; {@code crane} is empty while its chunk is not loaded. */
    static CraneRoster.Entry entry(CraneMember member, Optional<StackerCraneBlockEntity> crane, boolean withTimes) {
        int number = member.number();
        if (crane.isEmpty())
            return new CraneRoster.Entry(number, CraneRoster.Activity.UNLOADED, 0, Optional.empty(), -1);
        StackerCraneBlockEntity dock = crane.get();
        if (dock.pauseReason() != CranePauseReason.NONE)
            return new CraneRoster.Entry(number, CraneRoster.Activity.PAUSED, 0, Optional.empty(), -1);
        if (member.stranded())
            return new CraneRoster.Entry(number, CraneRoster.Activity.STRANDED, 0, Optional.empty(), -1);
        CraneTrafficInfo.Line line = dock.trafficInfo().map(CraneTrafficInfo::line).orElse(CraneTrafficInfo.Line.NONE);
        int other = dock.trafficInfo().map(CraneTrafficInfo::otherCrane).orElse(0);
        boolean waitsForACrane = dock.trafficInfo().map(CraneTrafficInfo::waitsForACrane).orElse(false);
        if (line == CraneTrafficInfo.Line.MOVING_ASIDE)
            return new CraneRoster.Entry(number, CraneRoster.Activity.MOVING_ASIDE, other, Optional.empty(), -1);
        Optional<TransportJob<ItemKey, RackPosition>> job = dock.currentJob();
        if (job.isEmpty())
            return new CraneRoster.Entry(number, CraneRoster.Activity.IDLE, 0, Optional.empty(), -1);
        // Only a wait that names a crane reads "waiting for crane N"; any other hold shows the job it holds up.
        if (dock.isTrafficHeld() && waitsForACrane)
            return new CraneRoster.Entry(number, CraneRoster.Activity.WAITING, other, Optional.empty(), -1);
        OptionalLong ticks = withTimes ? dock.remainingTripTicks() : OptionalLong.empty();
        return new CraneRoster.Entry(number, activityOf(CraneJobSummary.of(job.get())),
                0, Optional.of(job.get().target()),
                ticks.isPresent() ? (int) Math.min(Integer.MAX_VALUE, RemainingTrip.seconds(ticks.getAsLong())) : -1);
    }

    /** The activity word of a job, which the target decides for a store job that hands items over (M17). */
    private static CraneRoster.Activity activityOf(CraneJobSummary job) {
        if (job.handsOver())
            return CraneRoster.Activity.HANDING_OVER;
        return switch (job.type()) {
            case STORE -> CraneRoster.Activity.STORING;
            case RETRIEVE -> CraneRoster.Activity.RETRIEVING;
            case SUPPLY -> CraneRoster.Activity.SUPPLYING;
            case COLLECT -> CraneRoster.Activity.COLLECTING;
            case FILL -> CraneRoster.Activity.FILLING;
        };
    }
}
