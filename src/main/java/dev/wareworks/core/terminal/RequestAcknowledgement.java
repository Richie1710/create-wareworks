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
 * <p>
 * <b>A list order carries one of these forward as a budget</b> (M23, issue #19, ADR-036). The Yes a player gives when
 * they press Fetch is spent down portion by portion ({@link #minus}), and a portion whose cost the remainder does not
 * cover is not made at all: the order stops and asks again. That is what keeps one Yes from paying for an unbounded
 * stream of requests, and it is why {@link #plus} exists — an answer tops the budget up instead of replacing it.
 * <p>
 * These numbers carry <b>no item</b>, which is why a list order keeps the budget of a single portion's answer under the
 * key it was given for ({@link ListOrder#budgetFor}): nothing here could tell a Yes about Iron's reserve from a Yes
 * about Copper's, and "an answer only ever authorises the question it was given" is a promise the caller has to keep.
 * The one number a whole list spends fungibly on purpose is {@link #produced()}, because the Fetch dialog asks about the
 * list's production total rather than about one entry's.
 *
 * @param any               "whatever it costs": the ctrl-click, which the design makes the way to skip the question
 * @param fromReserve       items of the requested key the player accepts taking out of its reserve
 * @param pastMaximum       result items the player accepts storing above the maximum
 * @param ingredientReserve items of other keys the player accepts spending out of <b>their</b> reserves, over all
 *                          ingredients of the production order the request would start
 * @param produced          result items the player accepts having <b>made</b> for them, i.e.
 *                          {@link RequestConfirmation#made()}. It is only ever asked about and only ever checked for
 *                          {@link RequestScope#LIST} (M23): a click starts production under the player's eyes, a list
 *                          order starts it while nobody is there. A plain click therefore leaves it at 0 and means
 *                          exactly what it meant before M23
 */
public record RequestAcknowledgement(boolean any, long fromReserve, long pastMaximum, long ingredientReserve,
                                    long produced) {
    /** No answer given: a plain click, which is what makes the terminal ask in the first place. */
    public static final RequestAcknowledgement NONE = new RequestAcknowledgement(false, 0L, 0L, 0L, 0L);

    /** "Do not ask, whatever it costs": the ctrl-click, and what a caller with no player to ask sends. */
    public static final RequestAcknowledgement ANY = new RequestAcknowledgement(true, 0L, 0L, 0L, 0L);

    public RequestAcknowledgement {
        fromReserve = Math.max(0L, fromReserve);
        pastMaximum = Math.max(0L, pastMaximum);
        ingredientReserve = Math.max(0L, ingredientReserve);
        produced = Math.max(0L, produced);
    }

    /**
     * The answer to a {@link RequestScope#CLICK} question, which is every answer from before M23: it accepts nothing
     * of what is only ever asked for a list order ({@link #produced()}), so a click reads back as exactly what it was.
     */
    public RequestAcknowledgement(boolean any, long fromReserve, long pastMaximum, long ingredientReserve) {
        this(any, fromReserve, pastMaximum, ingredientReserve, 0L);
    }

    /** Whether the player said anything at all, i.e. whether this is more than {@link #NONE}. */
    public boolean given() {
        return any || fromReserve > 0L || pastMaximum > 0L || ingredientReserve > 0L || produced > 0L;
    }

    /**
     * Whether this answer authorises {@code confirmation} as the answer to a plain click. A question that crosses
     * nothing is covered by every answer, including {@link #NONE} — that is how an ordinary request reaches the queue
     * without a round trip.
     */
    public boolean covers(RequestConfirmation<?> confirmation) {
        return covers(confirmation, RequestScope.CLICK);
    }

    /**
     * Whether this answer authorises {@code confirmation} for {@code scope}: every number the question names must be
     * one the player has already accepted.
     * <p>
     * {@link RequestScope#LIST} adds exactly one number to that comparison — what would be <b>made</b> — because a
     * list order starts production with nobody at the terminal (M23, issue #19). Everything else is the M15 round trip
     * unchanged, so a portion of a list order is asked about on precisely the same terms as a click.
     */
    public boolean covers(RequestConfirmation<?> confirmation, RequestScope scope) {
        if (confirmation == null || !confirmation.required(scope))
            return true;
        if (any)
            return true;
        return fromReserve >= confirmation.fromReserve() && pastMaximum >= confirmation.pastMaximum()
                && ingredientReserve >= confirmation.fromIngredientReserve()
                && (scope != RequestScope.LIST || produced >= confirmation.made());
    }

    /**
     * This answer with {@code more} added to it: how a list order's consent budget is topped up when the player
     * answers a question a portion raised (M23, ADR-036). {@link #ANY} on either side wins, because "whatever it
     * costs" cannot be narrowed by a number.
     */
    public RequestAcknowledgement plus(RequestAcknowledgement more) {
        if (more == null)
            return this;
        if (any || more.any)
            return ANY;
        return new RequestAcknowledgement(false, fromReserve + more.fromReserve, pastMaximum + more.pastMaximum,
                ingredientReserve + more.ingredientReserve, produced + more.produced);
    }

    /**
     * What is left of this answer once {@code cost} has been paid out of it, never less than nothing: how a list
     * order's budget is spent down as its portions are made (M23, ADR-036).
     * <p>
     * {@link #ANY} spends nothing, which is the whole meaning of a ctrl-click. Every other budget shrinks by the
     * numbers the portion really cost, so the one Yes a player gave for 1300 reserved items cannot pay for 1300 again
     * in the next portion — the order asks a second time instead.
     */
    public RequestAcknowledgement minus(RequestConfirmation<?> cost) {
        if (any || cost == null)
            return this;
        return new RequestAcknowledgement(false, fromReserve - cost.fromReserve(), pastMaximum - cost.pastMaximum(),
                ingredientReserve - cost.fromIngredientReserve(), produced - cost.made());
    }
}
