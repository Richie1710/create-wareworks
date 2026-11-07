package dev.wareworks.core.job;

import java.util.Objects;

/**
 * When a Warehouse Optimizer may move an item from one storage location to another (issue #46, ADR-079): the
 * <b>strictly better</b> rule, written once for the planner's relocation stage ({@link JobPlanner#plan}) and for the
 * optimizer's view of what could move, so the two can never disagree.
 * <p>
 * A target is better than a source for a key when
 * <ul>
 *   <li>its storage priority is <b>strictly higher</b>, and</li>
 *   <li>its store filter allows the key at all and is <b>no worse a match</b> than the source's
 *       ({@link FilterMatch#storeRank()}, lower is better).</li>
 * </ul>
 * The second half is what keeps the optimizer from undoing ordinary storing: {@code JobPlanner}'s store ranking puts the
 * filter <i>above</i> the priority, so cobblestone in a priority-0 chest dedicated to cobblestone is exactly where every
 * new cobblestone goes. Moving it up into a priority-5 general rack would only last until the next store. Consolidation
 * and item-type grouping, the two keys between the filter and the priority, describe what a location already holds
 * rather than how good a place it is, so they are not part of the rule; they rank the targets that pass it.
 * <p>
 * Everything else a target must be — not a fluid bay (a filled container would be drained into it), not the source,
 * loaded, reachable and not an alias of a shared inventory — is a property of the place rather than of the comparison,
 * and is answered where those properties are known ({@link PlannerInput#relocateTarget()}, {@link PlannerInput#available()}).
 * <p>
 * <b>It terminates.</b> Every move strictly raises an item's priority, so an item moves at most as many times as there
 * are priorities above its own, and two locations can never pass items back and forth.
 */
public final class RelocationRule {
    private RelocationRule() {
    }

    /**
     * Whether a location with {@code targetPriority} and {@code targetFilter} is a better place for a key than a location
     * with {@code sourcePriority} and {@code sourceFilter} (see the class documentation).
     */
    public static boolean isBetter(int sourcePriority, FilterMatch sourceFilter, int targetPriority,
            FilterMatch targetFilter) {
        Objects.requireNonNull(sourceFilter, "sourceFilter");
        Objects.requireNonNull(targetFilter, "targetFilter");
        return targetFilter.allowsStoring()
                && isBetter(sourcePriority, sourceFilter.storeRank(), targetPriority, targetFilter.storeRank());
    }

    /**
     * {@link #isBetter(int, FilterMatch, int, FilterMatch)} for a target whose filter is already known to allow the key,
     * with both filters given as their {@link FilterMatch#storeRank()}: the form the planner's ranked candidates carry.
     */
    public static boolean isBetter(int sourcePriority, int sourceFilterRank, int targetPriority, int targetFilterRank) {
        return targetPriority > sourcePriority && targetFilterRank <= sourceFilterRank;
    }
}
