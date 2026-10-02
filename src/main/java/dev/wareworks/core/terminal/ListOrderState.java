package dev.wareworks.core.terminal;

import java.util.Locale;
import java.util.Optional;

/**
 * Where a {@link ListOrder} stands ({@code docs/warehouse-system.md} §3.4.4, M23, issue #19, ADR-036). {@link #name()}
 * is the stable save name, so new constants may be added but existing ones must keep their names.
 * <p>
 * Only {@link #RUNNING} measures anything. That is the point of the other three: an order that is waiting for an
 * answer, an order that has given up and an order that is finished all cost exactly nothing per tick, which is the
 * hard answer to "the crane must never spin without progress".
 */
public enum ListOrderState {
    /**
     * The order is working the list off: it tops up its portions on {@code terminalListIntervalTicks} and is the only
     * state in which {@link ListOrder#due} can be true.
     */
    RUNNING,
    /**
     * A portion would cost more than the consent the player gave at Fetch covers, so nothing was requested and the
     * next player to open the screen is asked about that one item ({@code §3.6.6}, M15/M20's own panel). The order
     * measures nothing until it is answered.
     * <p>
     * The question itself is <b>never saved</b>: it is measured fresh, as every confirmation is, so an order that was
     * asking when the world was saved comes back {@link #RUNNING} and asks again
     * ({@link ListOrder#restore}).
     */
    ASKING,
    /**
     * Nothing was in flight and nothing moved for {@code terminalListStallTicks}, so the order stopped measuring
     * entirely until a player resumes it. An item the warehouse can neither stock nor produce ends here.
     */
    PARKED,
    /** Every line was delivered in full. The clipboard carries the tick marks and the order has nothing left to do. */
    DONE;

    private static final String LANG_PREFIX = "gui.terminal.list.state.";

    /** Whether the order still has work to do, whatever it is waiting for. */
    public boolean isOpen() {
        return this != DONE;
    }

    /** Whether the order is measuring, i.e. whether it offers portions and runs its interval. */
    public boolean isRunning() {
        return this == RUNNING;
    }

    /** Whether the order is waiting for a player: a question to answer, or a park to resume. */
    public boolean waitsForPlayer() {
        return this == ASKING || this == PARKED;
    }

    /** Relative lang key of this state's text, e.g. {@code gui.terminal.list.state.parked}. */
    public String langKey() {
        return LANG_PREFIX + name().toLowerCase(Locale.ROOT);
    }

    /** The state with the given save name, or empty for {@code null} or unknown names. */
    public static Optional<ListOrderState> byName(String name) {
        if (name == null)
            return Optional.empty();
        for (ListOrderState state : values()) {
            if (state.name().equals(name))
                return Optional.of(state);
        }
        return Optional.empty();
    }
}
