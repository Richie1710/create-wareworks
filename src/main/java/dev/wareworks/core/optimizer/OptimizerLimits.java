package dev.wareworks.core.optimizer;

/**
 * The bounds of a Warehouse Optimizer's work (issue #46, ADR-079): how many trips one press may cost, how many items one
 * optimizer may list and how many orders a warehouse keeps. The arithmetic only, so it can be tested without Minecraft.
 * <p>
 * The trip cap is the one configurable number ({@code optimizer.tripsPerRequest}); it is clamped here as well, rather
 * than trusting a file a player edits. The other two are constants because they bound what a save, a packet and a
 * planner input may hold.
 */
public final class OptimizerLimits {
    /** The fewest trips one press of Optimise may cost. */
    public static final int MIN_TRIPS = 1;
    /** The most trips one press of Optimise may cost: a long afternoon of tidying, never an endless one. */
    public static final int MAX_TRIPS = 512;
    /** The shipped trip cap: enough to tidy a few stacks of every listed item in one go. */
    public static final int DEFAULT_TRIPS = 32;
    /** How many items one optimizer lists, and so how many entries one order has at most. */
    public static final int MAX_ROWS = 16;
    /** How many orders one warehouse keeps at a time, running or finished: one per optimizer. */
    public static final int MAX_ORDERS = 8;
    /**
     * How many of an order's trips are remembered as on their way at once. A trip leaves the set when its crane reports
     * it finished, given up or lost, so this only bounds what a save could hold if those reports never came (a crane
     * broken while its chunk was away): far above the cranes one warehouse may have.
     */
    public static final int MAX_IN_FLIGHT = 64;

    private OptimizerLimits() {
    }

    /** {@code trips} clamped into {@value #MIN_TRIPS}..{@value #MAX_TRIPS}. */
    public static int clampTrips(int trips) {
        return Math.max(MIN_TRIPS, Math.min(MAX_TRIPS, trips));
    }
}
