package dev.wareworks.core.stock;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Why the warehouse stopped making one item, and what it cost (M15 part 2, issue #3) — the <b>safety stop</b>.
 * <p>
 * <b>The rule it exists for.</b> Ingredients a player's machine has swallowed are unrecoverable: nothing takes items
 * back out of a Mechanical Crafter, a Millstone or a mixer ({@code docs/warehouse-system.md} §3.5.4). An order that
 * ends with items delivered and no result therefore means one of two things, and the warehouse cannot tell them apart:
 * the machine is broken, or the pattern is wrong. Both get worse the more often they are repeated, so the <b>first</b>
 * such loss stops that item from being made again and hands the decision to the player. An order that never delivered
 * anything is never paused — one that gave up while the crane was still fetching cost nothing, and pausing for it would
 * stop every farm in a base whose kinetic network was off overnight.
 * <p>
 * <b>It covers every kind of order</b> (M20, ADR-032). M15 armed the stop only for an <i>automatic</i> order, because
 * that was the only order the warehouse repeated by itself. A production plan repeats just as well — a click, a redstone
 * pulse or a rule can all order the same chain into the same machine again — so the first loss of any order now stops
 * that item for everyone: a player's click, a port's pulse and a rule's own refill alike
 * ({@code ProductionPlanner} refuses to plan a paused item at any level). {@link Cause} is what says which kind of
 * order armed it, and that is what decides whether the pause can ever be forgotten without a player
 * ({@link Cause#isRuleBorn()}).
 * <p>
 * <b>What a pause does and does not do.</b> It stops <i>making that item</i>, nothing else. A rule keeps its maximum,
 * keeps its reserve, keeps its comparator signal and keeps its lamp — the player's own farm should go on running — and
 * every surface shows the paused state instead of the ordinary one ({@link StockRuleStatus#PAUSED}).
 * <p>
 * Pure Java with no Minecraft types; the content layer keys these by item and saves them with the controller, because
 * the controller is the one thing that is always loaded when an order could be started.
 *
 * @param cause       how the order ended
 * @param unrecovered ingredient items the crane had already dropped at the production station when it ended, i.e. what
 *                    this loss cost. It is deliberately <b>not</b> the whole order: what was never fetched was never
 *                    spent
 */
public record StockRulePause(Cause cause, long unrecovered) {
    /** How the order that armed the stop ended, and what kind of order it was. {@link #name()} is the stable save
     * name; append only. */
    public enum Cause {
        /**
         * An <b>automatic</b> order — a stock rule refilling its own minimum — made no progress for
         * {@code productionOrderTimeoutTicks} and gave up: the machine never took the ingredients, or never produced
         * anything from them.
         */
        TIMED_OUT,
        /**
         * An <b>automatic</b> order was cancelled — by a player, or because its production station was broken or turned
         * away. A cancellation has to mean <i>stop</i>, so it pauses the item rather than letting the warehouse start
         * the same order again on the next evaluation.
         */
        CANCELLED,
        /**
         * An order <b>somebody asked for</b> timed out with ingredients already in a machine (M20, ADR-032): a player's
         * click, a redstone request, or a step of a production plan. Unlike an automatic order such an order has no rule
         * behind it, so this pause has nothing it could be forgotten with ({@link #isRuleBorn()}) and only a player
         * lifts it.
         */
        ORDER_TIMED_OUT,
        /**
         * An order somebody asked for was cancelled or lost its station while its ingredients were already in a machine
         * (M20, ADR-032) — including a step whose plan was ended by another step failing. As with
         * {@link #ORDER_TIMED_OUT}, only a player lifts it.
         */
        ORDER_CANCELLED;

        private static final String LANG_PREFIX = "gui.keeper.paused.";

        /** Relative lang key of this cause's text, e.g. {@code gui.keeper.paused.timed_out}. */
        public String langKey() {
            return LANG_PREFIX + name().toLowerCase(Locale.ROOT);
        }

        /**
         * Whether an <b>automatic</b> order armed this pause, i.e. whether a stock rule is what was ordering.
         * <p>
         * Only such a pause may ever be forgotten without a player: deleting the rule that ordered is one of the two
         * documented ways back (M15), and the pause goes with the rule it belonged to. A pause armed by anything else
         * has no rule to be forgotten with — the item may well be governed by no rule at all — so forgetting it would
         * silently let the next click rebuild the same order into the same broken machine, which is the one thing the
         * safety stop exists to prevent (ADR-027, ADR-032).
         */
        public boolean isRuleBorn() {
            return this == TIMED_OUT || this == CANCELLED;
        }

        /**
         * The cause for an order that ended badly: {@code restock} says whether the <b>warehouse itself</b> was the one
         * ordering ({@code ProductionOrder#isRestock()}), {@code cancelled} whether it was given up rather than timed
         * out.
         */
        public static Cause of(boolean restock, boolean cancelled) {
            if (restock)
                return cancelled ? CANCELLED : TIMED_OUT;
            return cancelled ? ORDER_CANCELLED : ORDER_TIMED_OUT;
        }

        /** The cause with the given save name, or empty for {@code null} or an unknown name. */
        public static Optional<Cause> byName(String name) {
            if (name == null)
                return Optional.empty();
            for (Cause cause : values()) {
                if (cause.name().equals(name))
                    return Optional.of(cause);
            }
            return Optional.empty();
        }
    }

    public StockRulePause {
        Objects.requireNonNull(cause, "cause");
        unrecovered = Math.max(0L, unrecovered);
    }

    /** A pause caused by a timed-out order. */
    public static StockRulePause timedOut(long unrecovered) {
        return new StockRulePause(Cause.TIMED_OUT, unrecovered);
    }

    /** A pause caused by a cancelled order. */
    public static StockRulePause cancelled(long unrecovered) {
        return new StockRulePause(Cause.CANCELLED, unrecovered);
    }

    /**
     * Whether a stock rule armed this pause, so that deleting the rule may forget it
     * ({@link Cause#isRuleBorn()}).
     */
    public boolean isRuleBorn() {
        return cause.isRuleBorn();
    }
}
