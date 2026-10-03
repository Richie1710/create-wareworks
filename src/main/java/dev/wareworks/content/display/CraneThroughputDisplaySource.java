package dev.wareworks.content.display;

import java.util.ArrayList;
import java.util.List;

import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;

import dev.wareworks.content.crane.StackerCraneBlockEntity;
import dev.wareworks.core.crane.CraneThroughput;
import dev.wareworks.util.WareworksLang;
import net.minecraft.network.chat.MutableComponent;

/**
 * Display Link source "Crane Throughput" ({@code docs/warehouse-system.md} §10, {@code docs/stacker-crane.md} §9, M25,
 * issue #16, ADR-039): what the stacker crane of a warehouse <b>got done</b> in the minute behind it — trips, items,
 * how much of the minute it was working and how much of it went into swinging round corners.
 * <p>
 * Bound to the stacker crane dock, second after {@link CraneStatusDisplaySource}, which stays the preselected source of
 * every link a player has already hung on a dock. The two are deliberately separate rather than four more lines on
 * "Crane Status": that source already fills a four-row board, and {@link WarehouseDisplays#limit} drops the tail, so
 * appending to it would cost a player the lines they already had.
 * <p>
 * <b>Four fixed rows, or one.</b> The rows never come and go with their values — a row that appeared only when it was
 * above zero would shift the three below it, and on a four-row board one of them off the end. So a zero is printed
 * here, where the crane's goggle lines leave it out. While the rolling window is <b>not yet a full minute</b> the
 * source emits the single line "Measuring" instead: a board cannot carry the "of the last 23 s" caveat the goggles
 * carry, and a number about ten seconds shown as if it were about a minute is the one thing this source must not do.
 * <p>
 * <b>Read live on the server.</b> {@link StackerCraneBlockEntity#throughput()} is an O(1) snapshot of running sums, so
 * a pull is a handful of field reads and never a scan (ADR-026) — and it is deliberately <i>not</i> the record the dock
 * syncs for goggles, which only exists while a player is really looking at the dock. A board therefore reads the same
 * numbers whether or not anybody is wearing goggles.
 * <p>
 * It keeps Create's default {@link #getPassiveRefreshTicks()} of 100 ticks rather than the 20 of "Crane Status": a
 * rolling minute does not change meaningfully every second, and a board that flips its flaps to redraw identical
 * numbers is noise.
 */
public class CraneThroughputDisplaySource extends DisplaySource {
    /** Rows this source writes once the window holds a whole minute. */
    private static final int ROWS = 4;

    @Override
    public List<MutableComponent> provideText(DisplayLinkContext context, DisplayTargetStats stats) {
        if (!(context.getSourceBlockEntity() instanceof StackerCraneBlockEntity dock))
            return WarehouseDisplays.limit(
                    List.of(WareworksLang.translateDirect(WareworksLang.DISPLAY_THROUGHPUT_NO_CRANE)), stats);

        CraneThroughput measured = dock.throughput();
        if (!measured.isFullMinute())
            return WarehouseDisplays.limit(
                    List.of(WareworksLang.translateDirect(WareworksLang.DISPLAY_THROUGHPUT_MEASURING)), stats);

        List<MutableComponent> lines = new ArrayList<>(ROWS);
        lines.add(WareworksLang.translateDirect(WareworksLang.DISPLAY_THROUGHPUT_LINE_TRIPS,
                WareworksLang.number(measured.trips())));
        lines.add(WareworksLang.translateDirect(WareworksLang.DISPLAY_THROUGHPUT_LINE_ITEMS,
                WareworksLang.number(measured.items())));
        // Through the one key in the mod that carries a percent sign, like every other share (ADR-039): German puts a
        // space before it, and a row that wrote the sign itself would be the second place to get that wrong.
        lines.add(WareworksLang.translateDirect(WareworksLang.DISPLAY_THROUGHPUT_LINE_BUSY,
                WareworksLang.percent(measured.busyShare())));
        lines.add(WareworksLang.translateDirect(WareworksLang.DISPLAY_THROUGHPUT_LINE_TURNING,
                WareworksLang.percent(measured.turnShare())));
        return WarehouseDisplays.limit(lines, stats);
    }
}
