package dev.wareworks.core.warehouse;

import java.util.List;
import java.util.Objects;

/**
 * The result of one {@link AisleMembership#reconcile} call, in scan order. A member whose kind changed appears in both
 * lists (removed with the old kind, added with the new one).
 *
 * @param added   records that became members
 * @param removed records that are no longer members
 */
public record MembershipChanges(List<LocationRecord> added, List<LocationRecord> removed) {
    private static final MembershipChanges NONE = new MembershipChanges(List.of(), List.of());

    public MembershipChanges {
        added = List.copyOf(Objects.requireNonNull(added, "added"));
        removed = List.copyOf(Objects.requireNonNull(removed, "removed"));
    }

    public static MembershipChanges none() {
        return NONE;
    }

    public boolean isEmpty() {
        return added.isEmpty() && removed.isEmpty();
    }
}
