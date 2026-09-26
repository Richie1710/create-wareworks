package dev.wareworks.core.terminal;

/**
 * What a player has agreed to pay for a request at a warehouse terminal ({@code docs/warehouse-system.md} §3.6.6, M15
 * part 2, issue #3): the answer to a {@link RequestConfirmation}, stated as the numbers it named rather than as a bare
 * "yes".
 * <p>
 * <b>Why numbers and not a flag.</b> A warehouse moves between the tick that asks and the tick that answers: a crane
 * can promise the items in between, another player can raise a reserve, a production pattern can be rewritten. A flag
 * would carry an old "yes" into a new situation. These numbers are compared with the cost the server measures
 * <b>again</b> when the confirmed request arrives ({@link #covers}), so an answer only ever authorises the question it
 * was given — a request that has grown more expensive is asked about a second time instead of being carried out.
 * <p>
 * <b>What this is and is not.</b> It is consent, not permission. Nothing here unlocks anything a player could not
 * reach anyway: a reserve never holds items back from the player standing at the terminal
 * ({@code core.stock.StockAccess}), and the maximum is their own cap. That is what makes {@link #ANY} — the answer a
 * ctrl-click sends, the "do not ask me" the design asks for — a legitimate thing for a client to say on its own. What
 * the server keeps to itself is the decision that a question is <b>needed</b> and the numbers it names: a client can
 * neither invent a reassuring question nor have a crossing request carried out without saying, in numbers, that its
 * player accepted the cost.
 *
 * @param any               "whatever it costs": the ctrl-click, which the design makes the way to skip the question
 * @param fromReserve       items of the requested key the player accepts taking out of its reserve
 * @param pastMaximum       result items the player accepts storing above the maximum
 * @param ingredientReserve items of other keys the player accepts spending out of <b>their</b> reserves, over all
 *                          ingredients of the production order the request would start
 */
public record RequestAcknowledgement(boolean any, long fromReserve, long pastMaximum, long ingredientReserve) {
    /** No answer given: a plain click, which is what makes the terminal ask in the first place. */
    public static final RequestAcknowledgement NONE = new RequestAcknowledgement(false, 0L, 0L, 0L);

    /** "Do not ask, whatever it costs": the ctrl-click, and what a caller with no player to ask sends. */
    public static final RequestAcknowledgement ANY = new RequestAcknowledgement(true, 0L, 0L, 0L);

    public RequestAcknowledgement {
        fromReserve = Math.max(0L, fromReserve);
        pastMaximum = Math.max(0L, pastMaximum);
        ingredientReserve = Math.max(0L, ingredientReserve);
    }

    /** Whether the player said anything at all, i.e. whether this is more than {@link #NONE}. */
    public boolean given() {
        return any || fromReserve > 0L || pastMaximum > 0L || ingredientReserve > 0L;
    }

    /**
     * Whether this answer authorises {@code confirmation}: every number the question names must be one the player has
     * already accepted. A question that crosses nothing is covered by every answer, including {@link #NONE} — that is
     * how an ordinary request reaches the queue without a round trip.
     */
    public boolean covers(RequestConfirmation<?> confirmation) {
        if (confirmation == null || !confirmation.required())
            return true;
        if (any)
            return true;
        return fromReserve >= confirmation.fromReserve() && pastMaximum >= confirmation.pastMaximum()
                && ingredientReserve >= confirmation.fromIngredientReserve();
    }
}
