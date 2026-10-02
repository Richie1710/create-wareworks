package dev.wareworks.core.terminal;

import java.util.List;
import java.util.Objects;

/**
 * What a delivery did to a {@link ListOrder} ({@code docs/warehouse-system.md} §3.4.4, M23, issue #19): how much of it
 * the order claimed, which lines it finished, and whether it was the delivery that finished the whole list.
 * <p>
 * <b>{@link #completed()} is the tick-mark instruction.</b> For each line in it the caller re-reads the clipboard,
 * checks that the entry at that line's page and index still shows that very item, and only then writes
 * {@code checked = true}. Nothing in the order depends on that write succeeding: the lines stay authoritative either
 * way, so a clipboard that cannot be ticked off costs a message and not the order ({@code §3.4.4}, risk 1). Writing a
 * tick twice is harmless, which is what lets a caller re-assert the ticks of every
 * {@link ListOrder#completedLines()} after a reload.
 * <p>
 * <b>{@link #finished()} is the one-shot edge</b> into {@link ListOrderState#DONE}: it is true for the one delivery
 * that completed the last open line and never again, so the "list complete" message, the deregistration at the
 * controller and the sound are fired exactly once however often deliveries are credited afterwards.
 *
 * @param absorbed  items of the delivery this order claimed. It is bounded by what the credited lines were really
 *                  owed, so a request shared with a player's own click (ADR-020) never hands the list more than its
 *                  own portions asked for
 * @param completed the lines that reached their full amount with this delivery, in list order
 * @param finished  whether this delivery was the one that made the whole order {@link ListOrderState#DONE}
 * @param <K>       item key type
 */
public record ListCredit<K>(int absorbed, List<ListLine<K>> completed, boolean finished) {
    public ListCredit {
        absorbed = Math.max(0, absorbed);
        completed = List.copyOf(Objects.requireNonNull(completed, "completed"));
    }

    /** The answer for a delivery no line of this order was waiting for. */
    public static <K> ListCredit<K> none() {
        return new ListCredit<>(0, List.of(), false);
    }

    /** Whether the delivery belonged to this order at all. */
    public boolean isEmpty() {
        return absorbed == 0;
    }
}
