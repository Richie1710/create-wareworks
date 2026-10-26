package dev.wareworks.core.crafting;

/**
 * The amount field of a warehouse terminal's crafting panel counts <b>result items</b>, the way it does everywhere else
 * in the terminal, while a craft fetch is measured, sent and stored in <b>crafts</b> (issue #33,
 * {@code docs/warehouse-system.md} §3.4.5, ADR-062 amendment). These two conversions are the only bridge between them.
 * <p>
 * Items become crafts <b>rounded up</b>: four Stone Bricks are one craft of a recipe that makes four, five are two. It
 * is the rule a production order follows ({@code ProductionPattern#runsFor}), so a player who asks for N items never
 * gets fewer, and at most one craft's worth more. A recipe that makes one item counts the same either way.
 * <p>
 * Pure Java with no Minecraft types; the client feeds it the count of the result the server resolved for the grid.
 */
public final class CraftAmounts {
    private CraftAmounts() {
    }

    /**
     * The crafts that make at least {@code items} result items, {@code perCraft} per craft: at least 1, rounded up,
     * saturating at {@link Integer#MAX_VALUE}.
     *
     * @param items    the result items asked for; anything below 1 counts as 1
     * @param perCraft result items one craft makes; anything below 1 counts as 1 (no result known)
     */
    public static int craftsFor(long items, int perCraft) {
        long wanted = Math.max(1L, items);
        long per = Math.max(1, perCraft);
        // Divided before adding, so an absurd amount cannot overflow on its way to saturating.
        long crafts = wanted / per + (wanted % per == 0L ? 0L : 1L);
        return (int) Math.min(Integer.MAX_VALUE, crafts);
    }

    /**
     * The result items {@code crafts} crafts make, {@code perCraft} per craft: what a panel line about the grid's
     * result shows for a number the server counts in crafts.
     *
     * @param crafts   crafts; anything below 0 counts as 0
     * @param perCraft result items one craft makes; anything below 1 counts as 1 (no result known)
     */
    public static long itemsFor(int crafts, int perCraft) {
        return (long) Math.max(0, crafts) * Math.max(1, perCraft);
    }
}
