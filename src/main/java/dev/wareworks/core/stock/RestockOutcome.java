package dev.wareworks.core.stock;

import java.util.Locale;

/**
 * What automatic restocking did — or did not do — for one stock rule in one evaluation (M15 part 2, issue #3).
 * <p>
 * <b>A rule with a minimum is a standing order.</b> When the warehouse has less of the item than the minimum calls for
 * and a production station of the same aisle holds a pattern for it, the warehouse orders the product by itself: the
 * very same production order M11 already runs, only without a request behind it. This enum is the one value that says
 * what happened, and it is the value every surface reports — the keeper's screen and goggles, the controller's goggles,
 * the terminal row and the aisle display.
 * <p>
 * <b>Exactly one outcome is reported per rule</b>, in the order {@code RestockPlanner} tests them:
 * {@link #NOT_GOVERNING}, {@link #PAUSED}, {@link #SATISFIED}, {@link #DISABLED}, {@link #ORDER_OPEN},
 * {@link #ORDERS_BUSY}, {@link #NO_PATTERN}, {@link #NO_ROOM}, {@link #WAITING_FOR_INGREDIENTS}, {@link #DEFERRED},
 * {@link #ORDERED}. The structural answers come first because they make every later question meaningless.
 * <p>
 * <b>Only {@link #ORDERED} ever spends anything.</b> Every other value is a statement about the world, and a rule that
 * cannot order keeps its lamp, its comparator and its reserve: "waiting for ingredients" is the normal, healthy state
 * of a line that has run out of feedstock, not a fault.
 * <p>
 * <b>Derived state, never serialized.</b> The outcome is recomputed by every restock pass
 * ({@code stockRuleIntervalTicks}) and is neither saved nor written to a client as an ordinal: what travels is the
 * {@link StockRuleStatus} it {@link #refine}s the rule into, plus the outcome's {@link #langKey()} for the one line that
 * names it. The enums with a wire or save contract are {@link StockRuleStatus} (ordinal, append-only) and
 * {@link StockRulePause.Cause} (stable name). The order of the values here is therefore free, and it says what the
 * planner does rather than what any format needs.
 */
public enum RestockOutcome {
    /** The rule applies nothing at all (shadowed, inert, or without a number), so it orders nothing either. */
    NOT_GOVERNING,
    /**
     * The rule is <b>paused</b>: one of its automatic orders ended with ingredients already handed to a machine and
     * nothing coming back, so the warehouse stopped ordering for it and waits for the player
     * ({@link StockRulePause}). Everything else about the rule keeps working — the maximum still caps, the reserve
     * still holds back, the comparator still calls for the item.
     */
    PAUSED,
    /**
     * Nothing to do: the rule has no minimum, or what the warehouse has and has already sent for
     * ({@link StockLevels#pipeline()}) meets it.
     */
    SATISFIED,
    /** Automatic restocking is switched off by configuration ({@code maxRestockOrders} or {@code maxRestockOrdersPerRule} is 0). */
    DISABLED,
    /**
     * This rule already has as many automatic orders open as it may ({@code maxRestockOrdersPerRule}, 1 by default).
     * The warehouse is already making the item and simply waits; this is what stops one hungry rule from ordering the
     * same thing twice while the first run is still in the machine.
     */
    ORDER_OPEN,
    /**
     * The aisle has reached {@code maxRestockOrders} automatic orders, or its production order queue
     * ({@code maxProductionOrders}) is full, so no further order can be started for <b>any</b> rule right now.
     */
    ORDERS_BUSY,
    /**
     * No production station of the aisle holds a pattern that makes this item, so the warehouse cannot make it at all.
     * The minimum still calls for it — that is what the keeper's comparator is for — but the player's own farm, not
     * the warehouse, has to answer.
     */
    NO_PATTERN,
    /**
     * A pattern exists and the ingredients may well be there, but the rule's own <b>maximum</b> leaves no room for a
     * whole run of it: the shortfall is smaller than what one run makes.
     * <p>
     * An order is not started for it, because a pattern cannot be cut to fit and the surplus above a cap never leaves
     * the warehouse again: the rule would report {@link StockRuleStatus#AT_MAXIMUM} for ever and a warehouse input
     * holding the item would back up. "Keep exactly 64 planks, made four at a time" is a pair of numbers only a player
     * can resolve — raise the maximum, or lower the minimum to a multiple of the run.
     */
    NO_ROOM,
    /**
     * A pattern exists, but its ingredients are not available <b>to automation</b>: they are missing, promised to
     * something else, or held back by a reserve ({@link StockAccess#AUTOMATION}). Nothing is ordered and nothing is
     * spent; {@code RestockDecision#missingIngredient} names the item the player has to supply.
     */
    WAITING_FOR_INGREDIENTS,
    /**
     * This rule would have ordered, but another rule's order goes first in this evaluation. One order is started per
     * pass, because every order changes what the ingredients of the next one are worth and a second order planned
     * against the stale numbers would promise the same items twice. The next pass ({@code stockRuleIntervalTicks})
     * reconsiders it against the new state.
     */
    DEFERRED,
    /** An automatic production order was started for this rule. */
    ORDERED;

    private static final String LANG_PREFIX = "gui.keeper.restock.";

    /** Whether this outcome started a production order. */
    public boolean ordered() {
        return this == ORDERED;
    }

    /**
     * Whether the warehouse is actively making the item for this rule right now: it just ordered, or an order it
     * started earlier is still running.
     */
    public boolean isOrdering() {
        return this == ORDERED || this == ORDER_OPEN;
    }

    /**
     * Whether the rule would order but something stops it, so a player can do something about it. {@link #SATISFIED},
     * {@link #NOT_GOVERNING} and {@link #DEFERRED} are not blocked: the first two have nothing to order and the third
     * orders in a moment.
     */
    public boolean isBlocked() {
        return this == PAUSED || this == DISABLED || this == ORDERS_BUSY || this == NO_PATTERN || this == NO_ROOM
                || this == WAITING_FOR_INGREDIENTS;
    }

    /**
     * The status this outcome refines {@code base} into — the one value a lamp, a badge or a tooltip shows.
     * <p>
     * <b>{@link #PAUSED} wins over everything</b>, including a maximum that is biting at the same moment: a paused
     * rule is the one state a player has to act on, and it stays visible until they do.
     * {@link StockRuleStatus#WAITING_FOR_INGREDIENTS} only ever replaces {@link StockRuleStatus#BELOW_MINIMUM},
     * because it says <i>why</i> that shortfall is not closing. {@link StockRuleStatus#ORDERING} also replaces
     * {@link StockRuleStatus#SATISFIED}, because a rule whose minimum is met only by an order that is still running
     * is not "within its limits" in any way a player would recognise — the racks are empty and the crane is working.
     * A maximum or a reserve that bites at the same moment always keeps its place: it is the more actionable
     * message.
     */
    public StockRuleStatus refine(StockRuleStatus base) {
        if (base == null)
            return StockRuleStatus.NO_ITEM;
        if (this == PAUSED)
            return StockRuleStatus.PAUSED;
        // "The warehouse is making more of this" replaces both of the answers the three numbers can give while an
        // order runs: below the minimum, and satisfied only because that order counts towards it
        // ({@code StockLevels#pipeline()}). A maximum or a reserve that bites at the same moment is the more
        // actionable message and keeps its place.
        if (isOrdering())
            return base == StockRuleStatus.BELOW_MINIMUM || base == StockRuleStatus.SATISFIED
                    ? StockRuleStatus.ORDERING : base;
        if (base != StockRuleStatus.BELOW_MINIMUM)
            return base;
        return this == WAITING_FOR_INGREDIENTS ? StockRuleStatus.WAITING_FOR_INGREDIENTS : base;
    }

    /**
     * Relative lang key of this outcome's text, e.g. {@code gui.keeper.restock.waiting_for_ingredients} — the one
     * externally visible form of an outcome, and the only reason {@link #name()} matters.
     */
    public String langKey() {
        return LANG_PREFIX + name().toLowerCase(Locale.ROOT);
    }
}
