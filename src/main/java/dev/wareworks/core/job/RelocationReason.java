package dev.wareworks.core.job;

import java.util.Optional;

/**
 * Why a Warehouse Optimizer's item ended with nothing (more) to move (issue #53, ADR-087): decided on the server, by the
 * planner's relocation stage from the very candidates it searched ({@link JobPlanner#plan}), or by the controller when
 * nothing of the item may move at all. A player reads it on the item's row instead of a bare "Nothing to move", so the
 * cases are the ones a player can tell apart and do something about. Saved and sent by {@link #name()}.
 */
public enum RelocationReason {
    /** The warehouse holds none of the item: the one case a row still says "Nothing to move". */
    NONE_IN_STOCK,
    /**
     * Everything the warehouse holds of it is spoken for: open requests, production ingredients, owed containers, a
     * stock rule's reserve, or other trips already carrying it.
     */
    SPOKEN_FOR,
    /** It lies only where the crane cannot reach it now: an unloaded chunk, a broken rail, a place that refused it. */
    NOT_REACHABLE,
    /**
     * Every storage location that could take it has the same priority as where it lies: the default, priority 0
     * everywhere. The optimizer only moves up to a strictly higher priority (ADR-079), so it has no target.
     */
    SAME_PRIORITY,
    /** Its places differ in priority, and it already lies at the highest one there is. */
    NO_HIGHER_PRIORITY,
    /**
     * Places with a higher priority exist, and their filters reject it or fit it worse than where it lies: a dedicated
     * place is never emptied into a general one, which ordinary storing would refill at once (RelocationRule).
     */
    FILTER_DOES_NOT_FIT,
    /**
     * Places with a higher priority would take it, and none has room for it now: full, holding another item type, or
     * refusing it live.
     */
    NO_ROOM_HIGHER,
    /**
     * Merge mode (issue #57, ADR-088): all of it already lies in one place, its home. Appended last, so the ordinals the
     * wire carries and the names saves hold stay valid.
     */
    ALREADY_ONE_PLACE,
    /** Merge mode: the home has no room for what the smallest other place holds, which is only moved whole. */
    HOME_FULL,
    /**
     * Merge mode: every other place has a higher priority than the home, or fits the item better (a store filter): an
     * item is never merged downwards.
     */
    HOME_WORSE;

    /** The reason with this save name, or empty. */
    public static Optional<RelocationReason> byName(String name) {
        for (RelocationReason reason : values()) {
            if (reason.name().equals(name))
                return Optional.of(reason);
        }
        return Optional.empty();
    }
}
