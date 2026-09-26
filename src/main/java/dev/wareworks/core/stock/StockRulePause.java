package dev.wareworks.core.stock;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Why the warehouse stopped restocking one item by itself, and what it cost (M15 part 2, issue #3) — the <b>safety
 * stop</b>.
 * <p>
 * <b>The rule it exists for.</b> Ingredients a player's machine has swallowed are unrecoverable: nothing takes items
 * back out of a Mechanical Crafter, a Millstone or a mixer ({@code docs/warehouse-system.md} §3.5.4). An automatic
 * order that ends with items delivered and no result therefore means one of two things, and the warehouse cannot tell
 * them apart: the machine is broken, or the pattern is wrong. Both get worse the more often they are repeated, so the
 * <b>first</b> such loss stops that rule from ordering again and hands the decision to the player. A rule that never
 * delivered anything is never paused — an order that gave up while the crane was still fetching cost nothing, and
 * pausing for it would stop every farm in a base whose kinetic network was off overnight.
 * <p>
 * <b>What a pause does and does not do.</b> It stops <i>ordering</i>, nothing else. The rule keeps its maximum, keeps
 * its reserve, keeps its comparator signal and keeps its lamp — the player's own farm should go on running — and every
 * surface shows the paused state instead of the ordinary one ({@link StockRuleStatus#PAUSED}).
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
    /** How the automatic order that paused a rule ended. {@link #name()} is the stable save name; append only. */
    public enum Cause {
        /**
         * The order made no progress for {@code productionOrderTimeoutTicks} and gave up: the machine never took the
         * ingredients, or never produced anything from them.
         */
        TIMED_OUT,
        /**
         * The order was cancelled — by a player, or because its production station was broken or turned away. A
         * cancellation has to mean <i>stop</i>, so it pauses the rule rather than letting the warehouse start the same
         * order again on the next evaluation.
         */
        CANCELLED;

        private static final String LANG_PREFIX = "gui.keeper.paused.";

        /** Relative lang key of this cause's text, e.g. {@code gui.keeper.paused.timed_out}. */
        public String langKey() {
            return LANG_PREFIX + name().toLowerCase(Locale.ROOT);
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
}
