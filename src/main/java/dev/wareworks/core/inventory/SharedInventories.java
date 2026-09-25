package dev.wareworks.core.inventory;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Which storage locations read the same inventory ({@code docs/warehouse-system.md} §5): a double chest behind two
 * warehouse interfaces, or an item vault behind several. Without this, the stock index would count such an inventory
 * once per location.
 * <p>
 * Every location has an inventory <i>identity</i> (any value with {@code equals}/{@code hashCode}, e.g. Create's
 * inventory identifier of the attached block). Locations with equal identities share one inventory. The first location
 * assigned to an identity is its <b>canonical</b> location, which holds the counts in the stock index; the others are
 * <b>aliases</b> with no counts of their own. When the canonical location leaves the identity (removed, or its identity
 * changed), the oldest remaining alias is <b>promoted</b>; the owner hands the counts over to it.
 * <p>
 * Pure Java, not thread-safe (server thread only).
 *
 * @param <L> location type
 * @param <I> identity type
 */
public final class SharedInventories<L, I> {
    /**
     * Result of {@link #assign}.
     *
     * @param canonical       the canonical location of the assigned identity (the location itself unless it is an alias)
     * @param identityChanged whether the location had no identity or another one before
     * @param promoted        the location that became canonical of the location's previous identity, because the
     *                        location was that identity's canonical location and left it
     * @param <L>             location type
     */
    public record Assignment<L>(L canonical, boolean identityChanged, Optional<L> promoted) {
        public Assignment {
            Objects.requireNonNull(canonical, "canonical");
            Objects.requireNonNull(promoted, "promoted");
        }
    }

    private final Map<L, I> identities = new HashMap<>();
    /** Locations per identity in assignment order; the first one is canonical. Never empty. */
    private final Map<I, LinkedHashSet<L>> locations = new HashMap<>();

    /**
     * Sets the identity of {@code location} (after a fresh read of its inventory).
     *
     * @return the canonical location for the identity and what changed
     */
    public Assignment<L> assign(L location, I identity) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(identity, "identity");
        I previous = identities.get(location);
        if (identity.equals(previous))
            return new Assignment<>(locations.get(identity).getFirst(), false, Optional.empty());
        Optional<L> promoted = previous == null ? Optional.empty() : detach(location, previous);
        identities.put(location, identity);
        LinkedHashSet<L> sharing = locations.computeIfAbsent(identity, key -> new LinkedHashSet<>());
        sharing.add(location);
        return new Assignment<>(sharing.getFirst(), true, promoted);
    }

    /**
     * Forgets {@code location} (it is no storage location any more).
     *
     * @return the location that became canonical in its place, if it was canonical and had aliases
     */
    public Optional<L> remove(L location) {
        Objects.requireNonNull(location, "location");
        I previous = identities.remove(location);
        return previous == null ? Optional.empty() : detach(location, previous);
    }

    private Optional<L> detach(L location, I identity) {
        LinkedHashSet<L> sharing = locations.get(identity);
        if (sharing == null)
            return Optional.empty();
        boolean wasCanonical = sharing.getFirst().equals(location);
        sharing.remove(location);
        if (sharing.isEmpty()) {
            locations.remove(identity);
            return Optional.empty();
        }
        return wasCanonical ? Optional.of(sharing.getFirst()) : Optional.empty();
    }

    /** The identity last assigned to {@code location}. */
    public Optional<I> identityOf(L location) {
        return Optional.ofNullable(identities.get(Objects.requireNonNull(location, "location")));
    }

    /** The canonical location of {@code location}'s identity (the location itself if it is canonical). */
    public Optional<L> canonicalOf(L location) {
        I identity = identities.get(Objects.requireNonNull(location, "location"));
        return identity == null ? Optional.empty() : Optional.of(locations.get(identity).getFirst());
    }

    /** Whether another location holds the counts of {@code location}'s inventory. */
    public boolean isAlias(L location) {
        return canonicalOf(location).map(canonical -> !canonical.equals(location)).orElse(false);
    }

    /** All locations sharing {@code location}'s inventory, canonical first (just the location if it shares nothing). */
    public List<L> sharing(L location) {
        I identity = identities.get(Objects.requireNonNull(location, "location"));
        return identity == null ? List.of() : List.copyOf(locations.get(identity));
    }

    /** Number of locations with an identity. */
    public int size() {
        return identities.size();
    }

    /**
     * Number of locations whose inventory another location counts, i.e. {@link #size()} minus the number of distinct
     * inventories. A caller that shows "how many inventories does this hold" subtracts this from its location count,
     * because an alias never carries counts of its own.
     */
    public int aliasCount() {
        return identities.size() - locations.size();
    }

    public void clear() {
        identities.clear();
        locations.clear();
    }

    @Override
    public String toString() {
        return "SharedInventories[locations=" + identities.size() + ", inventories=" + locations.size() + "]";
    }
}
