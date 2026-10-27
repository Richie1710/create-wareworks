package dev.wareworks.core.kinetics;

/**
 * When a Wareworks kinetic consumer asks Create to recompute its kinetic network, and whether the result shows a stale
 * overstress (issue #38, ADR-071). Pure: the caller passes what its block entity and its network report, and
 * {@code content.kinetics.StaleOverstressCheck} does the asking.
 * <p>
 * Create keeps each member's overstress flag as that member saved or last received it. A load joins every member
 * through {@code KineticNetwork#addSilently} without a recomputation, and so do generators whose chunk loads again
 * while the rest of the network stays loaded; a steady network (water wheels with an unchanged flow, a fixed Rotation
 * Speed Controller) never recomputes on its own. A member that was overstressed once, for example while the generators'
 * chunk was unloaded, therefore stays overstressed with a network that has the capacity again.
 * <p>
 * The schedule: nothing while the member is not overstressed (a healthy network is never touched, so the recheck can
 * never cause what it repairs). Once it is overstressed, the first recheck comes {@value #FIRST_DELAY_TICKS} ticks
 * later, which after a load leaves the neighbouring chunks time to load and their generators time to join, and then
 * one every {@value #INTERVAL_TICKS} ticks for as long as it stays overstressed. A really overstressed network gets the
 * same totals every time and stays overstressed.
 */
public final class OverstressRecheck {
    /** Ticks from the first overstressed tick (after a load: the first tick) to the first recheck. */
    public static final int FIRST_DELAY_TICKS = 40;
    /** Ticks between two rechecks while the member stays overstressed. */
    public static final int INTERVAL_TICKS = 100;

    private int countdown = FIRST_DELAY_TICKS;

    /**
     * One server tick of the member.
     *
     * @param overStressed whether the member is overstressed now
     * @return whether to recompute the network in this tick
     */
    public boolean tick(boolean overStressed) {
        if (!overStressed) {
            countdown = FIRST_DELAY_TICKS;
            return false;
        }
        if (--countdown > 0)
            return false;
        countdown = INTERVAL_TICKS;
        return true;
    }

    /**
     * Whether a member is still flagged overstressed after its network recomputed totals that are not overstressed.
     * That happens when the recomputed totals equal the network's stored ones (for example a network started from the
     * save of a member that was fine), because Create then syncs nothing, so the totals must be synced explicitly.
     *
     * @param overStressed the member's flag after the recomputation
     * @param capacity     the network's recomputed capacity
     * @param stress       the network's recomputed stress
     */
    public static boolean staleAfterRecompute(boolean overStressed, float capacity, float stress) {
        return overStressed && capacity >= stress;
    }
}
