package dev.wareworks.content.station;

import dev.wareworks.content.controller.AisleAssignment;
import net.minecraft.nbt.CompoundTag;

/**
 * What the goggle tooltip of a warehouse stock keeper shows, as synced to clients ({@code docs/warehouse-system.md}
 * §3.6, M15).
 * <p>
 * <b>Numbers only.</b> A block entity's update tag is part of every chunk packet, so the items of the rules never
 * travel here — only how many rules there are and how many of them are doing something right now. The items live in
 * the keeper's screen, which is a menu payload to the one player who opened it
 * ({@code network.StockKeeperScreenPayload}).
 * <p>
 * The synced size is an address string and eleven small numbers. Records compare by value, so the server syncs only
 * visible changes; negative numbers are clamped to 0 and reading never throws.
 *
 * @param assignment    aisle address, misaligned or not part of an aisle
 * @param rules         rows of this keeper that hold a rule
 * @param governing     rules of this keeper that really apply their numbers in the aisle
 * @param belowMinimum  governing rules whose item is below its minimum (the keeper's comparator value)
 * @param atMaximum     governing rules whose item may not be stored any more
 * @param atReserve     governing rules that hold everything left back from the warehouse's own automation
 * @param withoutEffect rules that are shadowed by an earlier one or beyond the aisle's rule cap
 * @param ordering      rules the warehouse is currently making the item for by itself (M15 part 2, issue #3); a
 *                      subset of {@link #belowMinimum()}, because an order only runs while the rule is short
 * @param waiting       rules that are short, have a pattern, and cannot order because the ingredients are not
 *                      available to automation — also a subset of {@link #belowMinimum()}
 * @param paused        rules the safety stop is holding: an automatic order of theirs ended with ingredients already
 *                      in a machine and nothing coming back. A paused rule keeps counting as whatever its three numbers
 *                      say, so this is <b>not</b> a subset of anything — see {@link #pausedBelowMinimum()} — and it is
 *                      the one number that turns the lamp red
 * @param pausedBelowMinimum how many of {@link #paused()} are also counted in {@link #belowMinimum()}, which is most of
 *                      them and cannot simply be assumed: a paused rule whose stock a player topped up by hand is at
 *                      its maximum or satisfied. The tooltip needs the overlap to print the paused line as the
 *                      refinement it is instead of a second group a reader adds on top (M15 review fix)
 * @param linked        whether a loaded controller reads this keeper's rules at all
 */
public record StockKeeperGoggleSummary(AisleAssignment assignment, int rules, int governing, int belowMinimum,
                                       int atMaximum, int atReserve, int withoutEffect, int ordering, int waiting,
                                       int paused, int pausedBelowMinimum, boolean linked) {
    public static final StockKeeperGoggleSummary NONE =
            new StockKeeperGoggleSummary(AisleAssignment.NONE, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false);

    private static final String ASSIGNMENT = "Assignment";
    private static final String RULES = "Rules";
    private static final String GOVERNING = "Governing";
    private static final String BELOW_MINIMUM = "BelowMinimum";
    private static final String AT_MAXIMUM = "AtMaximum";
    private static final String AT_RESERVE = "AtReserve";
    private static final String WITHOUT_EFFECT = "WithoutEffect";
    private static final String ORDERING = "Ordering";
    private static final String WAITING = "Waiting";
    private static final String PAUSED = "Paused";
    private static final String PAUSED_BELOW = "PausedBelow";
    private static final String LINKED = "Linked";

    public StockKeeperGoggleSummary {
        if (assignment == null)
            assignment = AisleAssignment.NONE;
        rules = Math.max(0, rules);
        governing = Math.max(0, governing);
        belowMinimum = Math.max(0, belowMinimum);
        atMaximum = Math.max(0, atMaximum);
        atReserve = Math.max(0, atReserve);
        withoutEffect = Math.max(0, withoutEffect);
        ordering = Math.max(0, ordering);
        waiting = Math.max(0, waiting);
        paused = Math.max(0, paused);
        pausedBelowMinimum = Math.max(0, Math.min(pausedBelowMinimum, paused));
    }

    /** Whether any rule of this keeper bites right now, which is what its lamp is lit for. */
    public boolean bites() {
        return belowMinimum > 0 || atMaximum > 0 || atReserve > 0 || paused > 0;
    }

    /**
     * Whether the safety stop is holding a rule of this keeper — the <b>other</b> lamp, and the one state a player has
     * to act on ({@code WarehouseStockKeeperBlock#PAUSED}, M15 part 2).
     */
    public boolean isPaused() {
        return paused > 0;
    }

    /** Writes this summary into {@code tag}. Never throws. */
    public void write(CompoundTag tag) {
        CompoundTag assignmentTag = new CompoundTag();
        assignment.write(assignmentTag);
        tag.put(ASSIGNMENT, assignmentTag);
        tag.putInt(RULES, rules);
        tag.putInt(GOVERNING, governing);
        tag.putInt(BELOW_MINIMUM, belowMinimum);
        tag.putInt(AT_MAXIMUM, atMaximum);
        tag.putInt(AT_RESERVE, atReserve);
        tag.putInt(WITHOUT_EFFECT, withoutEffect);
        tag.putInt(ORDERING, ordering);
        tag.putInt(WAITING, waiting);
        tag.putInt(PAUSED, paused);
        tag.putInt(PAUSED_BELOW, pausedBelowMinimum);
        tag.putBoolean(LINKED, linked);
    }

    /** Reads a summary written by {@link #write}. Never throws; missing or invalid data reads as empty values. */
    public static StockKeeperGoggleSummary read(CompoundTag tag) {
        return new StockKeeperGoggleSummary(AisleAssignment.read(tag.getCompound(ASSIGNMENT)), tag.getInt(RULES),
                tag.getInt(GOVERNING), tag.getInt(BELOW_MINIMUM), tag.getInt(AT_MAXIMUM), tag.getInt(AT_RESERVE),
                tag.getInt(WITHOUT_EFFECT), tag.getInt(ORDERING), tag.getInt(WAITING), tag.getInt(PAUSED),
                tag.getInt(PAUSED_BELOW), tag.getBoolean(LINKED));
    }
}
