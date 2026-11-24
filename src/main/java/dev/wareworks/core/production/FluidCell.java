package dev.wareworks.core.production;

import java.util.Objects;

/**
 * One fluid ingredient of a production pattern (issue #55, ADR-089): which fluid and how many millibuckets one run of
 * the machine takes of it, e.g. "water, 250 mB" for a Basin recipe that wants a quarter bucket per run.
 * <p>
 * <b>A fluid cell is not an item cell.</b> It lives beside the 3 x 3 grid ({@link ProductionPattern#fluids()}), is never
 * a key of the item grid, never takes part in the filter choices of ADR-082 and is never merged with anything. The
 * fluid is named by its registry id as a plain string, so this class stays free of Minecraft types like the rest of
 * {@code core.*}; the content layer resolves the id and may find it unknown (a removed mod), which must never be a crash.
 * <p>
 * <b>Whole containers only.</b> The warehouse never moves fluid by itself. It carries <i>filled containers</i> (buckets)
 * to the station, and the player's own machine turns them into fluid ({@code docs/warehouse-system.md} §3.5). The number
 * of containers one order needs is therefore a whole number, {@link #containersFor}, rounded <b>up</b> with the real
 * size of the container the plan picked (a bucket holds 1000 mB, a modded tank item may hold more); whatever is left
 * over stays in the player's machine and is not tracked. That is why a value that divides 1000 is the one to prefer
 * ({@link #dividesBucket()}).
 *
 * @param fluid       the fluid's registry id, e.g. {@code minecraft:water}; never blank
 * @param millibuckets millibuckets one run takes, {@value #MIN_MB}..{@value #MAX_MB}
 */
public record FluidCell(String fluid, int millibuckets) {
    /** Smallest amount a fluid cell may name. */
    public static final int MIN_MB = 1;
    /**
     * Largest amount a fluid cell may name: the capacity of one segment of a Create Basin (1000 mB), which is as much of
     * one fluid as a recipe there can ask for.
     */
    public static final int MAX_MB = 1000;
    /** Longest fluid id a cell may name; what every payload and the screen state write as a bounded string. */
    public static final int MAX_ID_LENGTH = 128;
    /** Fluid cells one pattern may hold: a Basin recipe takes at most two fluids. */
    public static final int MAX_PER_PATTERN = 2;
    /** One bucket, the amount a value is compared with for {@link #dividesBucket()}. */
    public static final int BUCKET_MB = 1000;
    /** Amount a wheel notch changes a cell by. */
    public static final int STEP_SMALL = 50;
    /** Amount a Shift + wheel notch changes a cell by. */
    public static final int STEP_MEDIUM = 250;
    /** Amount a Ctrl + wheel notch changes a cell by. */
    public static final int STEP_LARGE = 1000;

    public FluidCell {
        Objects.requireNonNull(fluid, "fluid");
        if (fluid.isBlank())
            throw new IllegalArgumentException("a fluid cell needs a fluid");
        if (millibuckets < MIN_MB || millibuckets > MAX_MB)
            throw new IllegalArgumentException(
                    "millibuckets must be within " + MIN_MB + ".." + MAX_MB + ": " + millibuckets);
    }

    /** {@code millibuckets} clamped into what one cell may name, for values from a client or from save data. */
    public static int clampMb(int millibuckets) {
        return Math.min(Math.max(millibuckets, MIN_MB), MAX_MB);
    }

    /** A cell with a clamped amount, for untrusted input. */
    public static FluidCell sanitized(String fluid, int millibuckets) {
        return new FluidCell(fluid, clampMb(millibuckets));
    }

    /** This cell's amount moved by {@code notches} steps of {@code step} mB, clamped (the mouse wheel). */
    public int stepped(int notches, int step) {
        return clampMb((int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE,
                (long) millibuckets + (long) notches * step)));
    }

    /** The same fluid with another amount (clamped). */
    public FluidCell withMillibuckets(int amount) {
        return new FluidCell(fluid, clampMb(amount));
    }

    /**
     * Whether one run's amount divides a bucket evenly, so that whole buckets are consumed with nothing left over once
     * enough runs are made. When it does not (750 mB), a remainder collects in the player's machine; the tooltip warns.
     */
    public boolean dividesBucket() {
        return BUCKET_MB % millibuckets == 0;
    }

    /** Millibuckets {@code runs} runs take; a {@code long}, so no number of runs can overflow it. */
    public long totalFor(int runs) {
        if (runs < 1)
            throw new IllegalArgumentException("runs must be at least 1: " + runs);
        return (long) millibuckets * runs;
    }

    /**
     * Filled containers {@code runs} runs need when each container holds {@code containerMb} millibuckets:
     * {@code ceil(runs * mB / containerMb)}, never less than 1 for at least one run. The size is the real content of the
     * container the plan picked, never a constant: a 250 mB cell with 4 runs is one bucket, with 5 runs two.
     *
     * @throws IllegalArgumentException if {@code runs < 1} or {@code containerMb < 1}
     */
    public int containersFor(int runs, int containerMb) {
        if (containerMb < 1)
            throw new IllegalArgumentException("containerMb must be at least 1: " + containerMb);
        long total = totalFor(runs);
        long containers = (total + containerMb - 1) / containerMb;
        return (int) Math.min(Integer.MAX_VALUE, containers);
    }
}
