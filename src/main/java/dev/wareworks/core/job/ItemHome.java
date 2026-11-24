package dev.wareworks.core.job;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Where an item belongs, its <b>home</b> (issue #57, ADR-088): one rule, used by the planner's store ranking and by the
 * optimizer's merge mode, so a crane never spreads an item the optimizer has just gathered.
 * <ol>
 * <li>A place whose store filter names the item is its home ({@link FilterMatch#DEDICATED}); the store ranking already
 * puts the filter above everything below it.</li>
 * <li>Without such a filter the home is the place that already holds the most of the item, on the highest priority that
 * holds it at all. A tie goes to the place's <b>index order</b> (its position in the aisle's storage locations), not to
 * the travel time, so the home does not wander with the crane's position.</li>
 * </ol>
 * This class is the second rule and nothing else: the pure comparison both callers share.
 */
public final class ItemHome {
    private ItemHome() {
    }

    /** A place that holds some of the item: its storage priority, how much it holds, and its index order. */
    public record Holder(int priority, long held, int order) {
    }

    /**
     * The order of two holders at <b>one</b> priority: the one holding more first, a tie to the lower index. A place
     * holding none of the item counts as a holder only for the sake of the storing ranking, which passes 0 and 0 for it.
     */
    public static int compareHolding(long heldA, int orderA, long heldB, int orderB) {
        int byHeld = Long.compare(heldB, heldA);
        if (byHeld != 0)
            return byHeld;
        return heldA > 0 && heldB > 0 ? Integer.compare(orderA, orderB) : 0;
    }

    /** The full order of the home rule's second half: priority highest first, then {@link #compareHolding}. */
    public static final Comparator<Holder> ORDER = (a, b) -> {
        int byPriority = Integer.compare(b.priority(), a.priority());
        return byPriority != 0 ? byPriority : compareHolding(a.held(), a.order(), b.held(), b.order());
    };

    /** The home among the holders of one item (second half of the rule), or empty without any. */
    public static Optional<Holder> pick(List<Holder> holders) {
        return holders.stream().min(ORDER.thenComparingInt(Holder::order));
    }
}
