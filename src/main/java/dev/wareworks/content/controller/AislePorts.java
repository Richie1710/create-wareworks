package dev.wareworks.content.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.job.FilterMatch;
import dev.wareworks.core.port.PortSettings;

/**
 * The <b>port policies</b> of one aisle's warehouse ports — direction, rank, redstone behaviour and the item the port's
 * filter names — cached by rack position ({@code docs/warehouse-system.md} §3.2, M17, issue #12). Owned by one
 * {@link WarehouseControllerBlockEntity}; server thread only.
 * <p>
 * <b>Why a cache.</b> A continuous port has to be topped up without anything telling the controller to look
 * ({@code PortRedstone#isContinuous}), and an accepting port is a candidate of <b>every</b> store plan, asked about every
 * item type in an input buffer. Resolving a block entity per port per key per run would be a world lookup per candidate.
 * The policies are therefore read where the controller already touches a port — when it joins the aisle, when it is
 * restored from a save, and when a port announces that its settings changed ({@link WarehouseRegistry#portChanged}) — and
 * a pass or a plan then costs one map read per port. The redstone <b>signal</b> is deliberately not cached: it lives in
 * the port's block state, which is one {@code getBlockState} away and can never be stale.
 * <p>
 * <b>Every change announces itself, not only a player's.</b> The cache is never refreshed periodically, so an entry that
 * silently went stale would stay stale — and because a requesting port writes no rank key, the stale value can be the
 * permissive one. A port therefore announces its settings from its value-box callbacks <b>and</b> from
 * {@code WarehouseOutputBlockEntity#readStationData}, which is the only other way a live port's data changes: a
 * {@code /data merge block}, a schematic print or Create's zapper overwriting its block entity in place, none of which
 * runs {@code onLoad} or any callback.
 * <p>
 * <b>Only what is not the default is stored.</b> {@link PortSettings#DEFAULT} — request, on a pulse — is what every
 * warehouse output did before M17 and what the vast majority of them keep doing; such a port needs no entry, nothing to
 * iterate and nothing to remember, <b>including its filter</b>, which only a port that accepts or requests continuously
 * is ever asked about. An aisle whose ports are all plain outputs therefore holds an empty map, the controller's port pass
 * does nothing at all, and a store plan asks this cache one question that a miss on an empty map answers.
 * <p>
 * <b>"Not read yet" is not "the default"</b> (the M8 lesson, {@link AisleFilters}). Nothing here is persisted — the
 * settings live in the port's own block entity data — so after a world load the map starts empty while the ports are
 * still in unloaded chunks or simply have not been asked yet. A port that joined or was restored is therefore marked
 * <b>unread</b> and resolved once through the controller's own lookup; until then it is treated as the default, which is
 * the <b>safe</b> answer in both directions: a port that would request is not topped up for a few ticks, and a port that
 * would accept is not offered any items. The dangerous default — "assume it accepts" — is never taken, because exporting
 * items is irreversible.
 * <p>
 * <b>The filter of a port is a hard rule and a plain item.</b> A port's filter slot takes one concrete item or nothing
 * ({@code WarehouseOutputBlockEntity#isRequestable} refuses Create's list, attribute and package filters), so the cached
 * filter is one {@link ItemKey} and {@link #filterMatch} is an equality test — no {@code FilterItemStack}, no filter
 * evaluation and nothing that can throw. An unfiltered port accepts anything, a filtered one only its item.
 * <p>
 * <b>Index order.</b> The entries are kept in {@link RackPosition#ORDER}, not in the order the ports happened to be read,
 * so {@link #acceptingPorts} answers the same list before and after a reload and the planner's last tiebreaker makes
 * planning deterministic.
 */
final class AislePorts {
    /**
     * A cached port policy.
     *
     * @param policy the port's direction, rank and redstone behaviour
     * @param filter the item the port's filter names, or {@code null} for a port without one (accepts anything)
     */
    private record Entry(PortSettings policy, @Nullable ItemKey filter) {
        Entry {
            Objects.requireNonNull(policy, "policy");
        }
    }

    private final Map<RackPosition, Entry> settings = new TreeMap<>(RackPosition.ORDER);
    /** Ports whose policy has never been read into this cache (restored from a save, or freshly joined). */
    private final Set<RackPosition> unread = new LinkedHashSet<>();
    /** Game time before which a continuous port is not asked again, after it was refused. */
    private final Map<RackPosition, Long> retryAfter = new HashMap<>();

    /**
     * Stores the policy and the filter item of the port at {@code rack}, and marks that port read. A port with the
     * default policy needs no entry at all, whatever its filter: only an accepting or continuously requesting port is
     * ever asked about either.
     */
    void set(RackPosition rack, PortSettings policy, Optional<ItemKey> filter) {
        Objects.requireNonNull(rack, "rack");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(filter, "filter");
        unread.remove(rack);
        if (PortSettings.DEFAULT.equals(policy)) {
            settings.remove(rack);
            retryAfter.remove(rack);
            return;
        }
        settings.put(rack, new Entry(policy, filter.orElse(null)));
    }

    /**
     * The port at {@code rack} exists but its policy is unknown here (restored from a save, or it just joined): the
     * controller's next port pass resolves it instead of leaving it at the default for ever.
     */
    void markUnread(RackPosition rack) {
        Objects.requireNonNull(rack, "rack");
        if (!settings.containsKey(rack))
            unread.add(rack);
    }

    /** The port at {@code rack} left the aisle (or is no longer a port). */
    void remove(RackPosition rack) {
        Objects.requireNonNull(rack, "rack");
        settings.remove(rack);
        unread.remove(rack);
        retryAfter.remove(rack);
    }

    /** The aisle is gone or was replaced. */
    void clear() {
        settings.clear();
        unread.clear();
        retryAfter.clear();
    }

    /** The policy of the port at {@code rack}; {@link PortSettings#DEFAULT} for one that carries none or is unread. */
    PortSettings at(RackPosition rack) {
        Entry entry = settings.get(Objects.requireNonNull(rack, "rack"));
        return entry == null ? PortSettings.DEFAULT : entry.policy();
    }

    /** The signed rank of the port at {@code rack}; {@link PortSettings#REQUEST_RANK} for one this cache has not read. */
    int rankAt(RackPosition rack) {
        return at(rack).rank();
    }

    /**
     * What the filter of the port at {@code rack} says about {@code key}, for {@code PlannerInput#storeFilter};
     * {@code null} when {@code rack} is no cached port, which is what tells the controller to ask
     * {@link AisleFilters} instead.
     * <p>
     * A port without a filter is {@link FilterMatch#UNFILTERED} (it takes anything), a port whose filter names exactly
     * this key is {@link FilterMatch#DEDICATED} — so a filtered port outranks an unfiltered one among the ports of the
     * same class, which is ADR-021's dedication argument applied to ports — and anything else is
     * {@link FilterMatch#REJECTED} and is dropped before any live simulation.
     */
    @Nullable
    FilterMatch filterMatch(RackPosition rack, ItemKey key) {
        Entry entry = settings.get(Objects.requireNonNull(rack, "rack"));
        if (entry == null)
            return null;
        if (entry.filter() == null)
            return FilterMatch.UNFILTERED;
        return entry.filter().equals(key) ? FilterMatch.DEDICATED : FilterMatch.REJECTED;
    }

    /** Ports whose policy still has to be read, as a snapshot the caller may resolve while iterating. */
    List<RackPosition> unreadPorts() {
        return unread.isEmpty() ? List.of() : List.copyOf(unread);
    }

    /**
     * Ports that ask for items <b>continuously</b> — the requesting direction with a redstone behaviour that is not a
     * single pulse — in index order. These are the only ports a controller looks at without being told to.
     */
    List<RackPosition> continuousRequests() {
        return matching(policy -> policy.isRequesting() && policy.redstone().isContinuous());
    }

    /**
     * Ports that <b>accept</b> items, in index order, whatever their redstone gate says (M17): the candidates the
     * controller then gates one by one before handing them to the planner.
     */
    List<RackPosition> acceptingPorts() {
        return matching(policy -> !policy.isRequesting());
    }

    /** How many ports accept items, for the controller's goggles; 0 for an aisle of plain outputs. */
    int acceptingCount() {
        int count = 0;
        for (Entry entry : settings.values()) {
            if (!entry.policy().isRequesting())
                count++;
        }
        return count;
    }

    private List<RackPosition> matching(Predicate<PortSettings> wanted) {
        if (settings.isEmpty())
            return List.of();
        List<RackPosition> found = new ArrayList<>(settings.size());
        for (Map.Entry<RackPosition, Entry> entry : settings.entrySet()) {
            if (wanted.test(entry.getValue().policy()))
                found.add(entry.getKey());
        }
        return found;
    }

    /** Whether the port at {@code rack} may be asked again at {@code now} (see {@link #backOffUntil}). */
    boolean isDue(RackPosition rack, long now) {
        Long until = retryAfter.get(Objects.requireNonNull(rack, "rack"));
        return until == null || now >= until;
    }

    /**
     * The port at {@code rack} was refused: do not ask it again before {@code until}. Without this a continuous port
     * whose item is out of stock would submit and be refused on every single pass.
     */
    void backOffUntil(RackPosition rack, long until) {
        retryAfter.put(Objects.requireNonNull(rack, "rack"), until);
    }

    /** The port at {@code rack} was served: it may be asked again as soon as it waits for nothing. */
    void clearBackOff(RackPosition rack) {
        retryAfter.remove(Objects.requireNonNull(rack, "rack"));
    }
}
