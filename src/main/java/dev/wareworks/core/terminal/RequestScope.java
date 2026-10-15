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
 * ({@link RequestAcknowledgement#produced()}), and that decision is {@link #asksAboutProduction()}.
 * {@link RequestConfirmation#required()} and {@link RequestAcknowledgement#covers(RequestConfirmation)} without a scope
 * mean {@link #CLICK}, so every caller from before M23 asks and answers exactly what it did.
 * <p>
 * {@link #name()} travels on the wire ({@code TerminalConfirmPayload}), so new constants may be added but existing ones
 * must keep their names; a name a build does not know reads as {@link #CLICK}.
 */
public enum RequestScope {
    /** A player at the terminal, clicking one item: the M15 question, unchanged. */
    CLICK,
    /**
     * One portion of a list order, made for a clipboard rather than for a click. A question is raised for production
     * as well, because the player has left and the order is still running.
     */
    LIST,
    /**
     * One portion of a craft fetch: the second list order a warehouse terminal can run, which brings the ingredients of
     * the recipe laid in its crafting grid into its buffer (issue #24, slice 2). It asks about exactly what a
     * {@link #LIST} portion asks about, for the same reason — the portions are made pass after pass while the player
     * may be anywhere — and differs only in where the answer goes: to the craft order rather than to the clipboard's.
     */
    CRAFT;

    /**
     * Whether a request of this scope has to be agreed to before it has items <b>made</b>, and whether an answer to it
     * has to name that number. True for the two scopes whose requests are made by an order that keeps running on its
     * own ({@link #LIST}, {@link #CRAFT}); false for a {@link #CLICK}, which starts production under the player's eyes.
     */
    public boolean asksAboutProduction() {
        return this == LIST || this == CRAFT;
    }
}
