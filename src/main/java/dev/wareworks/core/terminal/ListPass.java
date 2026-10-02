package dev.wareworks.core.terminal;

/**
 * What one top-up pass over a {@link ListOrder} found ({@code docs/warehouse-system.md} §3.4.4, M23, issue #19).
 * <p>
 * These are the four answers a list order can give, and they are deliberately different things: "the destination is
 * full right now" must keep waiting, "nothing here can be served at all" must stop costing ticks, and "finished" must
 * happen exactly once. Telling them apart is what the status line at the terminal is built from, and what decides
 * whether the order parks.
 */
public enum ListPass {
    /**
     * At least one portion was granted: the crane has work and the order is making progress. The interval returns to
     * its normal length and the stall timer starts over.
     */
    OFFERED,
    /**
     * Nothing could be offered, but requests are open: everything that is left is already on its way, or the order
     * already holds as many requests as {@code terminalListOpenRequests} allows it. This is the "nothing fits right
     * now" answer — a full terminal buffer lives here, because a destination that accepts nothing is a planner skip
     * with a back-off and the request simply stays open ({@code NoJobReason.OUTPUT_FULL}). The order waits; the first
     * freed slot restarts the trip.
     */
    WAITING,
    /**
     * Lines are left, nothing is in flight, and every portion the pass offered was refused: right now the warehouse
     * can serve none of what is left. The interval backs off and the order parks once
     * {@code terminalListStallTicks} have passed without progress.
     */
    STARVED,
    /**
     * Every line was delivered in full. The order is {@link ListOrderState#DONE} and the controller stops ticking it.
     */
    COMPLETE
}
