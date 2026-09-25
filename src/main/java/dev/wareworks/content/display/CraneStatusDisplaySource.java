package dev.wareworks.content.display;

import java.util.ArrayList;
import java.util.List;

import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;

import dev.wareworks.content.crane.CraneGoggleInfo;
import dev.wareworks.content.crane.CranePauseReason;
import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.core.inventory.KeyCount;
import dev.wareworks.core.job.JobType;
import dev.wareworks.util.WareworksLang;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;

/**
 * Display Link source "Crane Status" ({@code docs/warehouse-system.md} §10): what the stacker crane of an aisle is
 * doing, what it carries and where it is going.
 * <p>
 * Bound to the stacker crane dock. It reads the dock's {@link CraneGoggleInfo}, which the crane already republishes on
 * every state change, so a pull is a field read however long the aisle is. A crane moves far faster than a warehouse
 * fills up, so this source refreshes five times as often as the other three ({@link #getPassiveRefreshTicks()}).
 */
public class CraneStatusDisplaySource extends DisplaySource {
    /** How often an unpowered link pulls the crane's state, in ticks (Create's own fast source, the stopwatch, uses 20). */
    private static final int REFRESH_TICKS = 20;

    @Override
    public List<MutableComponent> provideText(DisplayLinkContext context, DisplayTargetStats stats) {
        if (!(context.getSourceBlockEntity() instanceof StackerCraneBlockEntity dock))
            return EMPTY;

        CraneGoggleInfo info = dock.goggleInfo();
        List<MutableComponent> lines = new ArrayList<>(4);
        lines.add(WareworksLang.translateDirect(activityKey(info)));
        info.job().ifPresent(job -> {
            lines.add(WareworksLang.translateDirect(WareworksLang.DISPLAY_CRANE_LINE_JOB, job.item().getDescription(),
                    WareworksLang.number(job.amount())));
            // Without a linked controller the address has no aisle letter; CraneGoggleInfo already falls back to 03-07R.
            lines.add(WareworksLang.translateDirect(WareworksLang.DISPLAY_CRANE_LINE_TARGET, info.address(job.target())));
        });
        lines.add(heldLine(info));
        return WarehouseDisplays.limit(lines, stats);
    }

    /** Pausing beats the job: a paused crane that still has a job is standing still, not working. */
    private static String activityKey(CraneGoggleInfo info) {
        if (info.pauseReason() != CranePauseReason.NONE)
            return WareworksLang.DISPLAY_CRANE_PAUSED;
        return info.job().map(job -> jobKey(job.type())).orElse(WareworksLang.DISPLAY_CRANE_IDLE);
    }

    private static String jobKey(JobType type) {
        return switch (type) {
            case STORE -> WareworksLang.DISPLAY_CRANE_STORING;
            case RETRIEVE -> WareworksLang.DISPLAY_CRANE_RETRIEVING;
            case SUPPLY -> WareworksLang.DISPLAY_CRANE_SUPPLYING;
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
