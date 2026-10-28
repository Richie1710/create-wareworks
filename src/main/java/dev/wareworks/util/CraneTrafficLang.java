package dev.wareworks.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import dev.wareworks.content.controller.CraneCounts;
import dev.wareworks.content.controller.CraneRoster;
import dev.wareworks.content.crane.CraneTrafficInfo;
import dev.wareworks.content.crane.DockJoinStatus;
import net.createmod.catnip.lang.LangBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * The texts of several cranes on one warehouse (issue #14, ADR-075 §15): the dock's traffic lines, the controller's
 * crane list, the home point that names its crane, the terminal's crane line and the two display sources read off the
 * controller. Keys are relative to the {@code wareworks} namespace like every key of {@link WareworksLang}, generated in
 * English by {@code data.WareworksLangGen} and hand-written in {@code de_de.json}.
 * <p>
 * Kept apart from {@link WareworksLang} so that the whole vocabulary of the feature is one place to read. Methods that
 * add goggle lines are client only, like everything that measures the font.
 */
public final class CraneTrafficLang {
    /** {@code "Crane %1$s of %2$s"}: the dock's line in a warehouse with several cranes. */
    public static final String GOGGLES_CRANE_NUMBER = "gui.goggles.crane_number";
    /** {@code "Holding position"}: the status of a crane its traffic line says stands still. */
    public static final String GOGGLES_STATUS_HOLDING = "gui.goggles.crane_status_traffic.holding";
    /** {@code "Moving aside"}: the status of a crane that drives out of another crane's way. */
    public static final String GOGGLES_STATUS_MOVING_ASIDE = "gui.goggles.crane_status_traffic.moving_aside";
    /** {@code "Waiting for crane %1$s"}: a held path whose rail the ledger did not name. */
    public static final String GOGGLES_TRAFFIC_WAITING_FOR = "gui.goggles.crane_traffic.waiting_for";
    /** {@code "Reserved: %1$s to %2$s"}: the stretch of rails a crane holds beyond the blocks it stands on. */
    public static final String GOGGLES_RESERVED = "gui.goggles.crane_reserved";
    /** {@code "About %1$s s left"}: what the current job still takes, without any wait. */
    public static final String GOGGLES_TRIP_LEFT = "gui.goggles.crane_trip_left";
    /** {@code "About %1$s s left, not counting the wait"}: the same while another crane holds it up. */
    public static final String GOGGLES_TRIP_LEFT_WAITING = "gui.goggles.crane_trip_left_waiting";
    /** {@code "Levels from %1$s up are served by the other cranes"}: a crane whose mast is lower than the warehouse. */
    public static final String GOGGLES_MAST_SERVED = "gui.goggles.crane_mast_served";
    /** {@code "Cranes: %1$s (%2$s)"}: the head of the controller's crane list. */
    public static final String GOGGLES_CRANES = "gui.goggles.cranes";
    /** {@code "Docks left out: %1$s, this server allows %2$s cranes"}: docks the maximum kept out. */
    public static final String GOGGLES_CRANES_REFUSED = "gui.goggles.cranes_refused";
    /** Prefix of the controller's line per crane, e.g. {@code gui.goggles.crane_roster.waiting}. */
    public static final String GOGGLES_ROSTER_PREFIX = "gui.goggles.crane_roster.";
    /** {@code "%1$s: %2$s to %3$s, about %4$s s"}: a crane on a job, with the time it still takes. */
    public static final String GOGGLES_ROSTER_JOB = GOGGLES_ROSTER_PREFIX + "job";
    /** {@code "%1$s: %2$s to %3$s"}: a crane on a job whose time is not known. */
    public static final String GOGGLES_ROSTER_JOB_NO_TIME = GOGGLES_ROSTER_PREFIX + "job_no_time";
    /** {@code "Crane %1$s waits here"}: a home point paired with one crane of several. */
    public static final String GOGGLES_HOME_POINT_SERVING_CRANE = "gui.goggles.home_point_serving_crane";
    /** {@code "This warehouse has more home points than cranes"}: a home point no crane of several is paired with. */
    public static final String GOGGLES_HOME_POINT_SECOND_CRANES = "gui.goggles.home_point_second_cranes";
    /** Prefix of the sentence a dock that did not join shows, e.g. {@code gui.goggles.dock_join.max_cranes}. */
    public static final String GOGGLES_DOCK_JOIN_PREFIX = "gui.goggles.dock_join.";
    /** {@code "%1$s working"}, a part of the crane counts. */
    public static final String CRANES_WORKING = "gui.cranes.working";
    /** {@code "%1$s waiting"}, a part of the crane counts. */
    public static final String CRANES_WAITING = "gui.cranes.waiting";
    /** {@code "%1$s paused"}, a part of the crane counts. */
    public static final String CRANES_PAUSED = "gui.cranes.paused";
    /** {@code "%1$s idle"}, a part of the crane counts. */
    public static final String CRANES_IDLE = "gui.cranes.idle";
    /** {@code "%1$s cranes: %2$s"}: the terminal's crane line for a warehouse with several. */
    public static final String TERMINAL_CRANES = "gui.terminal.cranes";
    /** {@code "%1$s, waiting for crane %2$s"}: the activity row of "Crane Status" while another crane holds it. */
    public static final String DISPLAY_CRANE_WAITING_FOR = "display_source.crane.waiting_for";
    /** {@code "Moving aside for crane %1$s"}: the activity row of "Crane Status" of a crane giving way. */
    public static final String DISPLAY_CRANE_MOVING_ASIDE = "display_source.crane.moving_aside";
    /** {@code "%1$s %2$s"}: one crane's row of "Crane Status" read off the controller ("2 Storing"). */
    public static final String DISPLAY_CRANE_ROW = "display_source.crane.row";
    /** {@code "%1$s Waiting for %2$s"}: the row of a crane another one holds up. */
    public static final String DISPLAY_CRANE_ROW_WAITING = "display_source.crane.row_waiting";
    /** {@code "%1$s Moving aside for %2$s"}: the row of a crane giving way. */
    public static final String DISPLAY_CRANE_ROW_MOVING_ASIDE = "display_source.crane.row_moving_aside";
    /** {@code "Not loaded"}: a crane whose chunk is not loaded, on a row. */
    public static final String DISPLAY_CRANE_UNLOADED = "display_source.crane.unloaded";
    /** {@code "Cut off"}: a crane a closed rail cut off from its dock, on a row. */
    public static final String DISPLAY_CRANE_STRANDED = "display_source.crane.stranded";
    /** What separates the parts of the crane counts: a comma, which German and English both use. */
    public static final String PART_SEPARATOR = ", ";

    private CraneTrafficLang() {
    }

    /** Relative lang key of a roster line without a job, e.g. {@code gui.goggles.crane_roster.idle}. */
    public static String rosterKey(CraneRoster.Activity activity) {
        return GOGGLES_ROSTER_PREFIX + activity.name().toLowerCase(Locale.ROOT);
    }

    /** Relative lang key of a dock's reason for not being a crane of the warehouse it faces. */
    public static String dockJoinKey(DockJoinStatus.Reason reason) {
        return GOGGLES_DOCK_JOIN_PREFIX + reason.name().toLowerCase(Locale.ROOT);
    }

    /** "Crane 2 of 3". */
    public static LangBuilder craneNumber(int number, int craneCount) {
        return WareworksLang.translate(GOGGLES_CRANE_NUMBER, WareworksLang.number(number).style(ChatFormatting.GOLD),
                WareworksLang.number(craneCount)).style(ChatFormatting.GRAY);
    }

    /**
     * The traffic line of {@code info} (client only, goggles): why the crane waits and for whom, with the rail the other
     * crane holds; empty for {@link CraneTrafficInfo.Line#NONE}.
     *
     * @param railAddress {@code "A-07"} for a rail of the warehouse
     */
    public static Optional<LangBuilder> trafficLine(CraneTrafficInfo info, String railAddress) {
        CraneTrafficInfo.Line line = info.line();
        if (line == CraneTrafficInfo.Line.NONE)
            return Optional.empty();
        ChatFormatting colour = switch (line) {
            case STRANDED -> ChatFormatting.RED;
            case MOVING_ASIDE -> ChatFormatting.GRAY;
            default -> ChatFormatting.GOLD;
        };
        LangBuilder other = WareworksLang.number(info.otherCrane()).style(ChatFormatting.WHITE);
        LangBuilder built;
        if (line == CraneTrafficInfo.Line.PATH_HELD)
            built = info.hasHeldRail()
                    ? WareworksLang.translate(line.langKey(), other,
                            WareworksLang.text(railAddress).style(ChatFormatting.WHITE))
                    : WareworksLang.translate(GOGGLES_TRAFFIC_WAITING_FOR, other);
        else if (line.namesCrane())
            built = WareworksLang.translate(line.langKey(), other);
        else
            built = WareworksLang.translate(line.langKey());
        return Optional.of(built.style(colour));
    }

    /**
     * The status text that replaces the phase while traffic decides what the crane does: a crane held at the rails is
     * still in a travel phase, and "Travelling to the target" under a line that says it waits contradicts itself.
     * Empty when the phase tells the truth.
     */
    public static Optional<String> statusKey(CraneTrafficInfo info) {
        if (info.line() == CraneTrafficInfo.Line.MOVING_ASIDE)
            return Optional.of(GOGGLES_STATUS_MOVING_ASIDE);
        return info.line().isWaiting() ? Optional.of(GOGGLES_STATUS_HOLDING) : Optional.empty();
    }

    /** "Reserved: A-03 to B-02". */
    public static LangBuilder reserved(String from, String to) {
        return WareworksLang.translate(GOGGLES_RESERVED, WareworksLang.text(from).style(ChatFormatting.GOLD),
                WareworksLang.text(to).style(ChatFormatting.GOLD)).style(ChatFormatting.GRAY);
    }

    /** "About 14 s left", or "..., not counting the wait" while another crane holds this one up. */
    public static LangBuilder tripLeft(long seconds, boolean waiting) {
        return WareworksLang.translate(waiting ? GOGGLES_TRIP_LEFT_WAITING : GOGGLES_TRIP_LEFT,
                WareworksLang.number(seconds).style(ChatFormatting.GOLD)).style(ChatFormatting.GRAY);
    }

    /** "Levels from 5 up are served by the other cranes", for a mast that reaches {@code servedLevels} levels. */
    public static LangBuilder mastServed(int servedLevels) {
        return WareworksLang.translate(GOGGLES_MAST_SERVED, WareworksLang.number(servedLevels + 1))
                .style(ChatFormatting.DARK_GRAY);
    }

    /** "2 working, 1 waiting": every non-zero part of {@code counts}, idle last. */
    public static MutableComponent countParts(CraneCounts counts) {
        List<Component> parts = new ArrayList<>(4);
        addPart(parts, CRANES_WORKING, counts.working());
        addPart(parts, CRANES_WAITING, counts.waiting());
        addPart(parts, CRANES_PAUSED, counts.paused());
        addPart(parts, CRANES_IDLE, counts.idle());
        MutableComponent joined = Component.empty();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0)
                joined.append(PART_SEPARATOR);
            joined.append(parts.get(i));
        }
        return joined;
    }

    private static void addPart(List<Component> parts, String key, int count) {
        if (count > 0)
            parts.add(WareworksLang.translateDirect(key, WareworksLang.number(count)));
    }

    /** "Cranes: 3 (2 working, 1 waiting)", the head of the controller's crane list. */
    public static LangBuilder cranesHead(CraneCounts counts) {
        return WareworksLang.translate(GOGGLES_CRANES, WareworksLang.number(counts.total()).style(ChatFormatting.GOLD),
                WareworksLang.builder().add(countParts(counts)).style(ChatFormatting.WHITE)).style(ChatFormatting.GRAY);
    }

    /** "3 cranes: 2 working, 1 waiting", the terminal's crane line. */
    public static MutableComponent terminalCranes(CraneCounts counts) {
        return WareworksLang.translateDirect(TERMINAL_CRANES, WareworksLang.number(counts.total()),
                countParts(counts));
    }
}
