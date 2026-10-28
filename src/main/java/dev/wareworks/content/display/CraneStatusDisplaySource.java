package dev.wareworks.content.display;

import java.util.ArrayList;
import java.util.List;

import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;

import dev.wareworks.content.controller.CraneRoster;
import dev.wareworks.content.controller.CraneRosters;
import dev.wareworks.content.controller.WarehouseControllerBlockEntity;
import dev.wareworks.content.crane.CraneGoggleInfo;
import dev.wareworks.content.crane.CraneJobSummary;
import dev.wareworks.content.crane.CranePauseReason;
import dev.wareworks.content.crane.CraneTrafficInfo;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.core.inventory.KeyCount;
import dev.wareworks.util.CraneTrafficLang;
import dev.wareworks.util.WareworksLang;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Display Link source "Crane Status" ({@code docs/warehouse-system.md} §10): what the stacker crane of an aisle is
 * doing, what it carries and where it is going.
 * <p>
 * Bound to the stacker crane dock. It reads the dock's {@link CraneGoggleInfo}, which the crane already republishes on
 * every state change, so a pull is a field read however long the aisle is. A crane moves far faster than a warehouse
 * fills up, so this source refreshes five times as often as the other three ({@link #getPassiveRefreshTicks()}).
 * <p>
 * <b>Several cranes (issue #14, ADR-075 §15.3).</b> On a crane of a warehouse with several, the activity row says when
 * another crane holds it up — "Retrieving, waiting for crane 2" — in the same row, so a four-row board keeps every line
 * it had. The source is also bound to the <b>controller</b>, after the controller's own sources so every existing link
 * keeps its preselected one: there it shows the whole warehouse — one row per crane ("1 Storing", "2 Waiting for 1",
 * "3 Idle") for several cranes, and for one crane exactly the rows of its dock.
 */
public class CraneStatusDisplaySource extends DisplaySource {
    /** How often an unpowered link pulls the crane's state, in ticks (Create's own fast source, the stopwatch, uses 20). */
    private static final int REFRESH_TICKS = 20;

    @Override
    public List<MutableComponent> provideText(DisplayLinkContext context, DisplayTargetStats stats) {
        BlockEntity source = context.getSourceBlockEntity();
        if (source instanceof StackerCraneBlockEntity dock)
            return dockLines(dock.goggleInfo(), stats);
        if (source instanceof WarehouseControllerBlockEntity controller && !controller.isRemoved())
            return warehouseLines(controller, stats);
        return EMPTY;
    }

    /** The rows of one crane: activity, job, target and what it holds. */
    private static List<MutableComponent> dockLines(CraneGoggleInfo info, DisplayTargetStats stats) {
        List<MutableComponent> lines = new ArrayList<>(4);
        lines.add(activityLine(info));
        info.job().ifPresent(job -> {
            lines.add(WareworksLang.translateDirect(WareworksLang.DISPLAY_CRANE_LINE_JOB, job.item().getDescription(),
                    WareworksLang.number(job.amount())));
            // Without a linked controller the address has no aisle letter; CraneGoggleInfo already falls back to 03-07R.
            lines.add(WareworksLang.translateDirect(WareworksLang.DISPLAY_CRANE_LINE_TARGET, info.address(job.target())));
        });
        lines.add(heldLine(info));
        return WarehouseDisplays.limit(lines, stats);
    }

    /**
     * The whole warehouse off its controller: one row per crane for several cranes, the dock's own rows for one, and
     * "No crane" without a loaded dock.
     */
    private static List<MutableComponent> warehouseLines(WarehouseControllerBlockEntity controller,
            DisplayTargetStats stats) {
        if (controller.craneCount() < 2)
            return controller.linkedDockEntity().map(dock -> dockLines(dock.goggleInfo(), stats))
                    .orElseGet(() -> WarehouseDisplays.limit(
                            List.of(WareworksLang.translateDirect(WareworksLang.DISPLAY_THROUGHPUT_NO_CRANE)), stats));
        List<MutableComponent> rows = new ArrayList<>();
        for (CraneRoster.Entry entry : CraneRosters.entries(controller, false))
            rows.add(row(entry));
        return WarehouseDisplays.limit(rows, stats);
    }

    /** One crane's row: "1 Storing", "2 Waiting for 1", "3 Idle". */
    public static MutableComponent row(CraneRoster.Entry entry) {
        Object number = WareworksLang.number(entry.number());
        return switch (entry.activity()) {
            case WAITING -> WareworksLang.translateDirect(CraneTrafficLang.DISPLAY_CRANE_ROW_WAITING, number,
                    WareworksLang.number(entry.otherCrane()));
            case MOVING_ASIDE -> WareworksLang.translateDirect(CraneTrafficLang.DISPLAY_CRANE_ROW_MOVING_ASIDE, number,
                    WareworksLang.number(entry.otherCrane()));
            default -> WareworksLang.translateDirect(CraneTrafficLang.DISPLAY_CRANE_ROW, number,
                    WareworksLang.translateDirect(rowWordKey(entry.activity())));
        };
    }

    private static String rowWordKey(CraneRoster.Activity activity) {
        return switch (activity) {
            case STORING -> WareworksLang.DISPLAY_CRANE_STORING;
            case RETRIEVING -> WareworksLang.DISPLAY_CRANE_RETRIEVING;
            case SUPPLYING -> WareworksLang.DISPLAY_CRANE_SUPPLYING;
            case COLLECTING -> WareworksLang.DISPLAY_CRANE_COLLECTING;
            case HANDING_OVER -> WareworksLang.DISPLAY_CRANE_HANDING_OVER;
            case FILLING -> WareworksLang.DISPLAY_CRANE_FILLING;
            case PAUSED -> WareworksLang.DISPLAY_CRANE_PAUSED;
            case UNLOADED -> CraneTrafficLang.DISPLAY_CRANE_UNLOADED;
            case STRANDED -> CraneTrafficLang.DISPLAY_CRANE_STRANDED;
            case IDLE, WAITING, MOVING_ASIDE -> WareworksLang.DISPLAY_CRANE_IDLE;
        };
    }

    /**
     * The activity row: the activity word, "Retrieving, waiting for crane 2" while another crane holds this one up,
     * and "Moving aside for crane 1" while it gives way (issue #14). A pause still beats both.
     */
    public static MutableComponent activityLine(CraneGoggleInfo info) {
        MutableComponent activity = WareworksLang.translateDirect(activityKey(info));
        if (info.pauseReason() != CranePauseReason.NONE || info.traffic().isEmpty())
            return activity;
        CraneTrafficInfo traffic = info.traffic().get();
        if (traffic.line() == CraneTrafficInfo.Line.MOVING_ASIDE)
            return WareworksLang.translateDirect(CraneTrafficLang.DISPLAY_CRANE_MOVING_ASIDE,
                    WareworksLang.number(traffic.otherCrane()));
        // "Idle, waiting for crane 2" would say the same thing twice: an idle crane that cannot get to where it parks
        // shows its idle word only, and its goggles say why.
        if (info.job().isPresent() && traffic.waitsForACrane())
            return WareworksLang.translateDirect(CraneTrafficLang.DISPLAY_CRANE_WAITING_FOR, activity,
                    WareworksLang.number(traffic.otherCrane()));
        return activity;
    }

    /** Pausing beats the job: a paused crane that still has a job is standing still, not working. */
    private static String activityKey(CraneGoggleInfo info) {
        if (info.pauseReason() != CranePauseReason.NONE)
            return WareworksLang.DISPLAY_CRANE_PAUSED;
        return info.job().map(CraneStatusDisplaySource::jobKey).orElse(WareworksLang.DISPLAY_CRANE_IDLE);
    }

    /**
     * The activity of a job. The <b>target</b> decides for a store job (M17, issue #12): one whose target is an
     * accepting warehouse port is carrying items out of the warehouse, so a board that said "Storing" would contradict
     * the port's own goggles ("Handed over: N") and the crane's.
     */
    private static String jobKey(CraneJobSummary job) {
        if (job.handsOver())
            return WareworksLang.DISPLAY_CRANE_HANDING_OVER;
        return switch (job.type()) {
            case STORE -> WareworksLang.DISPLAY_CRANE_STORING;
            case RETRIEVE -> WareworksLang.DISPLAY_CRANE_RETRIEVING;
            case SUPPLY -> WareworksLang.DISPLAY_CRANE_SUPPLYING;
            // Fetching out of a machine is the opposite of storing, so it gets its own word (M18, issue #13).
            case COLLECT -> WareworksLang.DISPLAY_CRANE_COLLECTING;
            // A fluid request on its way (M31, issue #21): neither storing nor retrieving, so it says what it does.
            case FILL -> WareworksLang.DISPLAY_CRANE_FILLING;
        };
    }

    /**
     * "Holding Iron Ingot x64", or the goggles' own "Empty". The handling head carries the items of one job, so the
     * first entry is what it holds; a rerouted head never mixes item types.
     */
    private static MutableComponent heldLine(CraneGoggleInfo info) {
        List<KeyCount<Item>> held = info.held();
        if (held.isEmpty())
            return WareworksLang.translateDirect(WareworksLang.GOGGLES_EMPTY);
        KeyCount<Item> first = held.get(0);
        return WareworksLang.translateDirect(WareworksLang.DISPLAY_CRANE_LINE_HOLDING,
                WareworksLang.translateDirect(WareworksLang.GOGGLES_ITEM_COUNT, first.key().getDescription(),
                        WareworksLang.number(first.count())));
    }

    @Override
    public int getPassiveRefreshTicks() {
        return REFRESH_TICKS;
    }
}
