package dev.wareworks.core.job;

import java.util.Optional;

/**
 * What a {@link RetrievalRequest} promises its station (M31, issue #21, ADR-073). {@link #name()} is the stable save
 * name.
 * <p>
 * Both kinds ask for the same thing — a number of items of one key at one station — and live in the <b>one</b>
 * request queue; they differ in where the items come from, and therefore in what the warehouse has promised by
 * accepting them:
 * <ul>
 * <li>{@link #STOCK}: items that are already in the racks. Every request before M31 is one, and a save without a kind
 * reads as one.</li>
 * <li>{@link #FILL}: <b>filled containers that do not exist yet</b>. The key is the filled container (a lava bucket),
 * and the warehouse has promised the fluid in its bays and the empty containers in its racks instead: a {@code FILL}
 * job picks the empties, has a fluid bay fill them and goes on to the station that asked.</li>
 * </ul>
 * The two never stand in for each other. A lava bucket on a shelf is an item and serves a {@code STOCK} request; the
 * lava in a bay serves a {@code FILL} request. That is also why a fill request never merges into a stock request of
 * the same key and station ({@link RequestQueue#add}): the two promise different things.
 */
public enum RequestKind {
    /** Items out of the racks: every request before M31. */
    STOCK,
    /** Filled containers the warehouse makes out of a bay's fluid and an empty container from the racks (M31). */
    FILL;

    /** The kind with the given save name, or empty for {@code null} or unknown names. */
    public static Optional<RequestKind> byName(String name) {
        if (name == null)
            return Optional.empty();
        for (RequestKind kind : values()) {
            if (kind.name().equals(name))
                return Optional.of(kind);
        }
        return Optional.empty();
    }
}
