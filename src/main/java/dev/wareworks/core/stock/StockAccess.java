package dev.wareworks.core.stock;

/**
 * Who is taking items out of the warehouse, which is the only thing a rule's <b>reserve</b> distinguishes (M15,
 * issue #3).
 * <p>
 * A reserve protects the last items of a key <b>from the warehouse's own automation</b>, not from the player who owns
 * it:
 * <ul>
 *   <li>{@link #AUTOMATION} — a redstone-triggered request at a warehouse output, including the <b>ingredients</b> of a
 *       production order such a request starts, and (later) automatic restocking. These all stop at the reserve: that
 *       is what keeps a hand-built line from draining the last 32 andesite alloy overnight, and it holds whether the
 *       automation asks for the reserved item itself or for something a pattern would make out of it.</li>
 *   <li>{@link #PLAYER} — a request a player makes at a warehouse terminal. It is served immediately, down to the last
 *       item, and the terminal says in the row that it is going below the reserve. A player is never told "not in
 *       stock" about items they can see.</li>
 * </ul>
 * This is the one place where the user's decision differs from the first design study, which had it the other way
 * round; both surfaces (goggles and terminal) have to make clear which of the two cases a player is in.
 */
public enum StockAccess {
    /** The warehouse itself: a redstone request at an output, or automatic restocking. Stops at the reserve. */
    AUTOMATION,
    /** A player at a terminal. May take the reserve, and is shown that it does. */
    PLAYER
}
