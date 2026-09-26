package dev.wareworks.core.stock;

/**
 * What one stock rule is doing right now — the single value the lamp of a warehouse stock keeper, its goggle line, the
 * row in its screen and the badge in the terminal all read (M15, issue #3).
 * <p>
 * Four of the values are <b>structural</b>: the rule governs nothing at all, whatever the stock is
 * ({@link #NO_ITEM}, {@link #NO_WAREHOUSE}, {@link #INERT}, {@link #SHADOWED}, {@link #NO_LIMITS}). The other four
 * answer "what does this rule say about the item right now" and are computed by {@link StockRule#statusFor}.
 * <p>
 * <b>Exactly one value is reported</b>, in this precedence: {@link #BELOW_MINIMUM}, {@link #AT_MAXIMUM},
 * {@link #AT_RESERVE}, {@link #SATISFIED}. The first two cannot both hold — a maximum is never below a minimum
 * (a rule raises it, {@link StockRuleAdjustment#MAXIMUM_RAISED_TO_MINIMUM}), so a pipeline below the minimum always
 * leaves room under the maximum — but a full warehouse whose stock is all reserved really is both at its maximum and
 * at its reserve, and then the storage cap is the more actionable of the two.
 * <p>
 * <b>Automatic restocking refines three of them</b> (M15 part 2): {@link #ORDERING} and
 * {@link #WAITING_FOR_INGREDIENTS} say why a {@link #BELOW_MINIMUM} shortfall is or is not closing, and {@link #PAUSED}
 * replaces whatever the numbers say, because the safety stop is the one state a player has to act on
 * ({@link RestockOutcome#refine}). What a rule <b>counts as</b> — the keeper's comparator, the controller's
 * below-minimum count — is always taken from the unrefined status, so a paused rule keeps calling for its item exactly
 * as it did before it was paused.
 * <p>
 * {@link #name()} is a stable name and the content layer puts the ordinal on the wire, so new values are <b>appended</b>
 * and the precedence stays in the code rather than in the declaration order.
 */
public enum StockRuleStatus {
    /**
     * The row has no item in its filter slot, so there is nothing to govern. Never returned by
     * {@link StockRule#statusFor} (a rule always has a key); the content layer reports it for an empty row.
     */
    NO_ITEM,
    /**
     * The stock keeper is not part of a warehouse, so no controller reads this rule. Set by the content layer, never
     * by the rule itself.
     */
    NO_WAREHOUSE,
    /** The rule is above the aisle's rule cap ({@code maxStockRules}) and applies nothing until the cap is raised. */
    INERT,
    /**
     * An earlier rule already governs this item, so this one applies nothing. Told apart from {@link #INERT} because
     * the cure is different: remove the duplicate, rather than raise a cap.
     */
    SHADOWED,
    /**
     * The rule has an item but no number switched on, so it neither signals, caps nor reserves anything. A row a
     * player started and did not finish, not an error.
     */
    NO_LIMITS,
    /**
     * The pipeline (stocked plus inbound plus expected) is under the minimum: the keeper's comparator calls for this
     * item and, from M15 part 2, restocking may order it.
     */
    BELOW_MINIMUM,
    /**
     * The maximum leaves no room, so the warehouse stops accepting this item and an input holding it backs up on
     * purpose. Reported to the planner as {@code NoJobReason.AT_MAXIMUM}, never as "warehouse full".
     */
    AT_MAXIMUM,
    /**
     * Everything still available is held back from automation by the reserve: a redstone request gets nothing more,
     * while a player at a terminal is still served and is told that it goes below the reserve
     * ({@link StockAccess}).
     */
    AT_RESERVE,
    /** The rule governs the item and none of its three numbers bites at the moment. */
    SATISFIED,
    /**
     * Below the minimum, and the warehouse is <b>doing something about it</b>: an automatic production order for this
     * item is open (M15 part 2, {@link RestockOutcome#isOrdering()}). A refinement of {@link #BELOW_MINIMUM} that says
     * why the shortfall needs no attention.
     */
    ORDERING,
    /**
     * Below the minimum, a pattern exists, and the <b>ingredients are not available to automation</b> — missing,
     * promised elsewhere, or held back by a reserve ({@link RestockOutcome#WAITING_FOR_INGREDIENTS}). Nothing was
     * spent and nothing was ordered; this is the normal state of a line that has run out of feedstock, and the item a
     * player has to supply is named where there is room for it.
     */
    WAITING_FOR_INGREDIENTS,
    /**
     * The <b>safety stop</b>: one of this rule's automatic orders ended with ingredients already handed to a machine
     * and nothing coming back, so the warehouse stopped ordering for it and waits for the player
     * ({@link StockRulePause}). Everything else the rule does goes on — the maximum caps, the reserve holds back, the
     * comparator calls for the item — and this status outranks every other one, because it is the one a player has to
     * act on.
     */
    PAUSED;

    /** Whether this rule governs its item at all, i.e. whether any of its numbers can apply. */
    public boolean governs() {
        return switch (this) {
            case BELOW_MINIMUM, AT_MAXIMUM, AT_RESERVE, SATISFIED, ORDERING, WAITING_FOR_INGREDIENTS, PAUSED -> true;
            default -> false;
        };
    }

    /**
     * Whether the rule is <b>biting</b> right now: the one sentence that covers all three numbers, and what the
     * keeper's lamp is lit for. Below its minimum — however the warehouse is answering that shortfall — at its
     * maximum, or down to its reserve.
     */
    public boolean bites() {
        return this == BELOW_MINIMUM || this == AT_MAXIMUM || this == AT_RESERVE || this == ORDERING
                || this == WAITING_FOR_INGREDIENTS || this == PAUSED;
    }

    /** Whether the safety stop is holding this rule, which is a different lamp and a different line everywhere. */
    public boolean isPaused() {
        return this == PAUSED;
    }

    /**
     * Whether the status is a warning a player has to act on: a rule that cannot apply although it was configured
     * ({@link #NO_WAREHOUSE}, {@link #INERT}, {@link #SHADOWED}). {@link #NO_ITEM} and {@link #NO_LIMITS} are not
     * warnings: an unfinished row is a player's own business.
     */
    public boolean isWarning() {
        return this == NO_WAREHOUSE || this == INERT || this == SHADOWED;
    }
}
