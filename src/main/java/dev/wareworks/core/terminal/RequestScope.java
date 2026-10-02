package dev.wareworks.core.terminal;

/**
 * Who makes a request, and therefore what the terminal has to ask about before it carries it out
 * ({@code docs/warehouse-system.md} §3.4.4, M23, issue #19).
 * <p>
 * <b>Why a request needs a scope at all.</b> The two boundaries a stock keeper sets are the player's own
 * ({@link RequestConfirmation#fromReserve()}, {@link RequestConfirmation#pastMaximum()}), so a single click that
 * crosses one of them is worth a question and a click that starts a production order is not: the player is standing
 * there, watching it happen, and the order's result is what they asked for. A <b>list order</b> is the same stream of
 * ordinary requests with nobody watching — it runs on while the player walks to the building site — so starting
 * production is exactly the part that must be agreed to first ("producible items ask too", issue #19).
 * <p>
 * Nothing else differs. A portion of a list order is measured, clamped, queued, batched, reserved, prioritised and
 * refused by precisely the code a click goes through (ADR-020, ADR-036); the scope only decides whether
 * {@link RequestConfirmation#made()} is part of the question and whether an answer has to name it
 * ({@link RequestAcknowledgement#produced()}). {@link RequestConfirmation#required()} and
 * {@link RequestAcknowledgement#covers(RequestConfirmation)} without a scope mean {@link #CLICK}, so every caller from
 * before M23 asks and answers exactly what it did.
 */
public enum RequestScope {
    /** A player at the terminal, clicking one item: the M15 question, unchanged. */
    CLICK,
    /**
     * One portion of a list order, made for a clipboard rather than for a click. A question is raised for production
     * as well, because the player has left and the order is still running.
     */
    LIST
}
