package dev.wareworks.core.production;

/**
 * Why a production order at a Recipe Selector station fetches nothing right now, or fetches only a little (ADR-085,
 * design §5.1). Sent to clients by {@link #name()}, so a constant is never renamed.
 * <p>
 * Two kinds of reason matter for the order's deadline ({@link #queues()}): a <b>queue</b> means nothing is wrong, the
 * order waits its turn behind another result or behind a hold the player has to lift, and its deadline is paused; every
 * other reason is something physical or a fault, and the station's head order keeps a running deadline for it, so a
 * line that never drains ends visibly instead of hanging.
 */
public enum SelectorWait {
    /** Nothing to wait for. */
    NONE(false),
    /** Admitted, but only for one run: the first order after a switch to an untrusted far factory (design §7.5). */
    PROBE_ONE_RUN(false),
    /** Another order of the station comes first. */
    BEHIND(true),
    /** Another result's ingredients are still in the line. */
    LINE_BUSY(true),
    /** Waits for the probe order of the station to bring back its first result. */
    PROBE_BEHIND(true),
    /** The station is stopped until the player confirms (design §8). */
    HOLD(true),
    /** The station's buffer (or tank) still holds items, so the line cannot switch yet. */
    BUFFER_NOT_EMPTY(false),
    /** A crane is still on its way to the station with ingredients. */
    CRANE_INBOUND(false),
    /** A Packager of the station still holds or queues a box. */
    PACKAGER_BUSY(false),
    /** The switch to this order's result is being made. */
    SWITCHING(false),
    /** The local selector has not confirmed the machine's filter yet. */
    ARMING(false),
    /** The station has no Recipe Selector linked. */
    NO_SELECTOR(false),
    /** The linked Recipe Selector is in an unloaded chunk. */
    SELECTOR_NOT_LOADED(false),
    /** The Recipe Selector faces no machine it can set. */
    NO_MACHINE(false),
    /** The selector's machine is in an unloaded chunk. */
    MACHINE_NOT_LOADED(false),
    /** The machine is still working on the previous result. */
    MACHINE_BUSY(false),
    /** The machine's filter is a filter item, which a selector never overwrites. */
    FILTER_ITEM(false),
    /** The machine refused the filter the selector set. */
    FILTER_REFUSED(false),
    /** Another selector or another mod writes the same filter. */
    CONTESTED(false),
    /** Slip mode: no Packager packs from the station. */
    NO_PACKAGER(false),
    /** Slip mode: a Packager of the station is linked and ignores redstone. */
    PACKAGER_LINKED(false),
    /** A Crafter Feeder is attached to the station, which a selector station cannot use. */
    FEEDER_ATTACHED(false),
    /** The station has no return input bound. */
    NO_RETURN_INPUT(false),
    /** The station's return input is unloaded or gone. */
    RETURN_INPUT_MISSING(false);

    private final boolean queues;

    SelectorWait(boolean queues) {
        this.queues = queues;
    }

    /**
     * Whether an order waiting for this reason is merely <b>queued</b>: nothing is wrong, so its deadline is paused
     * ({@code ProductionOrders#timeOut} with the order among the also-blocked ones).
     */
    public boolean queues() {
        return queues;
    }
}
