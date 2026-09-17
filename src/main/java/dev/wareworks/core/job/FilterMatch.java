package dev.wareworks.core.job;

/**
 * What a storage location's store filter says about one item key ({@code docs/warehouse-system.md} §3.1, ADR-021).
 * <p>
 * Filters restrict <b>storing</b> only: {@link JobPlanner#plan} and {@link JobPlanner#planReroute} ask
 * {@link PlannerInput#storeFilter()} before they rank a storage candidate, while retrieval never consults it, so items
 * already inside a location can always be fetched even when its filter no longer matches them.
 * <p>
 * <b>Selecting and excluding filters are told apart</b> (M8 review fix). Create's list and attribute filters have a
 * deny mode, in which {@code test} answers <i>true</i> for everything the filter does <b>not</b> list. Such a location
 * accepts the item, but its owner did not dedicate it to that item, so it is {@link #ALLOWED} and ranks like an
 * unfiltered location instead of beating consolidation and item-type grouping for every item in the warehouse.
 */
public enum FilterMatch {
    /**
     * The location carries a filter that <b>selects</b> the key (an allow list, an attribute rule it matches, a
     * matching package address, a plain item filter). Such a location ranks <b>above</b> every {@link #UNFILTERED} one,
     * so dedicated storage fills before general storage.
     */
    DEDICATED,
    /**
     * The location carries a filter that accepts the key without selecting it: a deny list (or a deny-mode attribute
     * filter) that simply does not exclude it. It may be stored here, but it gets no ranking bonus — "anything but
     * iron" is not a dedication to gold.
     */
    ALLOWED,
    /** The location carries no filter, so it accepts everything (the Create convention for an empty filter slot). */
    UNFILTERED,
    /**
     * The location carries a filter and that filter rejects the key. On the storing paths the planner skips such a
     * location before the capacity estimate and before any live simulation, so it costs neither a live call nor a
     * remembered refusal. A {@code RETRIEVE} reroute is the one exception: putting items back where they came from is
     * not choosing where new items live, so there they are ranked last instead of dropped (§8).
     */
    REJECTED;

    /** Whether the planner may store the key at this location. */
    public boolean allowsStoring() {
        return this != REJECTED;
    }

    /** Whether a filter explicitly selects the key (the ranking bonus of a dedicated location). */
    public boolean isDedicated() {
        return this == DEDICATED;
    }

    /**
     * The first sort key of {@link JobPlanner}'s storage ranking: dedicated locations (0) before locations that merely
     * accept the item (1) before rejecting ones (2, reachable only on a retrieve reroute). Lower is better.
     */
    public int storeRank() {
        return switch (this) {
            case DEDICATED -> 0;
            case ALLOWED, UNFILTERED -> 1;
            case REJECTED -> 2;
        };
    }
}
