package dev.wareworks.content.crane;

import java.util.List;

import dev.wareworks.core.crane.CraneThroughput;
import dev.wareworks.util.WareworksLang;
import net.minecraft.network.chat.Component;

/**
 * The goggle lines of a crane's throughput (M25, issue #16, ADR-039): the headline, the one share a player has to act
 * on, and — on the dock — where the busy share went and what came of it.
 * <p>
 * This lives in the content layer and not on {@link CraneThroughput} itself, because a goggle line is a
 * {@link Component} and {@code core.*} is pure Java. The record stays the single source of the numbers; this class only
 * decides which of them are worth a line.
 * <p>
 * <b>Nothing is printed at zero.</b> A window with nothing in it never reaches here at all
 * ({@link CraneGoggleInfo}'s compact constructor normalises it away), and the blocked line is left out while its share
 * rounds to zero — the mod's rule throughout, and the reason a parked crane's tooltip is byte-for-byte what it was
 * before this feature existed.
 */
final class CraneThroughputLines {
    private CraneThroughputLines() {
    }

    /**
     * Adds the throughput lines for goggles (client only).
     *
     * @param measured what the rolling window holds; never an empty one
     * @param detailed whether to add the breakdown and the trip counts below the headline
     * @param bending  whether the warehouse has more than one aisle, which decides whether the breakdown names the
     *                 turning share
     */
    static void addGoggleLines(List<Component> tooltip, int indent, CraneThroughput measured, boolean detailed,
            boolean bending) {
        WareworksLang.craneBusy(measured).forGoggles(tooltip, indent);
        if (measured.blockedShare() > 0)
            WareworksLang.craneBlocked(measured).forGoggles(tooltip, indent);
        if (!detailed)
            return;
        WareworksLang.craneBreakdown(measured, bending).forGoggles(tooltip, indent);
        WareworksLang.craneTrips(measured).forGoggles(tooltip, indent);
    }
}
