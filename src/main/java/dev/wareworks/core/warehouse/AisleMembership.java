package dev.wareworks.core.warehouse;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;

/**
 * The membership list of one aisle: which rack positions hold aligned members (storage locations, input and output
 * stations) and which hold misaligned ones ({@code docs/warehouse-system.md} §3.3, §4).
 * <p>
 * <b>No world searches.</b> The list changes only in {@link #reconcile}, which the controller calls while the list is
 * dirty. Dirty sources are member notifications ({@link #markDirty}: only that position is probed), geometry changes
 * ({@link #markAllDirty}: a full scan of the rack positions) and layout changes ({@link #invalidateAll}: a full scan that
 * also forgets positions it cannot verify). More than {@link #maxDirtyPositions()} single dirty positions escalate to a
 * full scan, so the pending work stays bounded.
 * <p>
 * <b>Unloaded positions.</b> A probe of {@link RackProbe#UNLOADED} keeps whatever is known about the position (a
 * persisted record or misaligned flag), unless the list was invalidated, because the records then refer to another
 * layout.
 * <p>
 * <b>Round robin.</b> {@link #nextStorageLocation()} cycles over the storage locations in {@link RackPosition#ORDER},
 * for the controller's snapshot reconciliation (§5).
 * <p>
 * Not thread-safe (server thread only).
 */
public final class AisleMembership {
    /** Default for the number of single dirty positions that are probed individually before a full scan is used. */
    public static final int DEFAULT_MAX_DIRTY_POSITIONS = 64;

    private final int maxDirtyPositions;
    private final TreeMap<RackPosition, LocationKind> members = new TreeMap<>(RackPosition.ORDER);
    private final TreeSet<RackPosition> storage = new TreeSet<>(RackPosition.ORDER);
    private final TreeSet<RackPosition> misaligned = new TreeSet<>(RackPosition.ORDER);
    private final Set<RackPosition> dirtyPositions = new HashSet<>();
    private int inputs;
    private int outputs;
    private int productions;
    private boolean fullScanPending;
    private boolean dropUnverified;
    @Nullable
    private RackPosition roundRobinCursor;

    public AisleMembership() {
        this(DEFAULT_MAX_DIRTY_POSITIONS);
    }

    /** @param maxDirtyPositions single dirty positions tolerated before a full scan is scheduled instead (≥ 0) */
    public AisleMembership(int maxDirtyPositions) {
        if (maxDirtyPositions < 0)
            throw new IllegalArgumentException("maxDirtyPositions must not be negative: " + maxDirtyPositions);
        this.maxDirtyPositions = maxDirtyPositions;
    }

    public int maxDirtyPositions() {
        return maxDirtyPositions;
    }

    // --- dirty state ---------------------------------------------------------------------------------------------

    /** Whether {@link #reconcile} has work to do. */
    public boolean isDirty() {
        return fullScanPending || !dirtyPositions.isEmpty();
    }

    /** Whether the next {@link #reconcile} scans every rack position. */
    public boolean isFullScanPending() {
        return fullScanPending;
    }

    /** A member at {@code position} was placed, removed, rotated or loaded: probe that position on the next reconcile. */
    public void markDirty(RackPosition position) {
        Objects.requireNonNull(position, "position");
        if (fullScanPending)
            return;
        dirtyPositions.add(position);
        if (dirtyPositions.size() > maxDirtyPositions) {
            dirtyPositions.clear();
            fullScanPending = true;
        }
    }

    /** The geometry changed (or the list was loaded): scan all rack positions, keeping what unloaded positions knew. */
    public void markAllDirty() {
        dirtyPositions.clear();
        fullScanPending = true;
    }

    /**
     * The layout the records refer to changed (dock position or aisle direction): scan all rack positions and forget
     * everything at positions that cannot be verified because they are not loaded.
     */
    public void invalidateAll() {
        markAllDirty();
        dropUnverified = true;
    }

    // --- reconciliation ------------------------------------------------------------------------------------------

    /**
     * Probes the dirty positions (or all rack positions of {@code geometry} for a full scan) and applies the results.
     * Records outside {@code geometry} are removed. Does nothing when not dirty.
     * <p>
     * <b>The probe may mark positions dirty again</b> (it is allowed to change the world: the warehouse terminal's
     * intake port is written from inside it, which notifies this list back through the controller). That is why the
     * pending work is <b>snapshotted before the loop</b> — the dirty set is copied and cleared up front — so a mark
     * raised during a pass is kept for the <i>next</i> {@link #reconcile} instead of being lost or probed twice, and
     * the loop can never see a concurrent modification. Do not move the clearing to the end of the pass.
     *
     * @param geometry the current aisle size
     * @param probe    classifies one rack position; called at most once per position, in {@link RackPosition#ORDER}.
     *                 May call {@link #markDirty} for the position it is probing (see above)
     * @return the added and removed records, in scan order
     */
    public MembershipChanges reconcile(AisleGeometry geometry, Function<RackPosition, RackProbe> probe) {
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(probe, "probe");
        if (!isDirty())
            return MembershipChanges.none();
        List<LocationRecord> added = new ArrayList<>();
        List<LocationRecord> removed = new ArrayList<>();
        boolean drop = dropUnverified;
        List<RackPosition> positions;
        if (fullScanPending) {
            for (RackPosition outside : outsideOf(geometry))
                forget(outside, removed);
            positions = geometry.rackPositions();
        } else {
            List<RackPosition> sorted = new ArrayList<>(dirtyPositions);
            sorted.sort(RackPosition.ORDER);
            positions = sorted;
        }
        fullScanPending = false;
        dropUnverified = false;
        dirtyPositions.clear();

        for (RackPosition position : positions) {
            if (!geometry.contains(position)) {
                forget(position, removed);
                continue;
            }
            RackProbe result = Objects.requireNonNull(probe.apply(position), "probe result");
            apply(position, result, drop, added, removed);
        }
        return new MembershipChanges(added, removed);
    }

    private List<RackPosition> outsideOf(AisleGeometry geometry) {
        List<RackPosition> outside = new ArrayList<>();
        for (RackPosition position : members.keySet()) {
            if (!geometry.contains(position))
                outside.add(position);
        }
        for (RackPosition position : misaligned) {
            if (!geometry.contains(position) && !members.containsKey(position))
                outside.add(position);
        }
        return outside;
    }

    private void apply(RackPosition position, RackProbe result, boolean drop, List<LocationRecord> added,
                       List<LocationRecord> removed) {
        switch (result) {
            case UNLOADED -> {
                if (drop)
                    forget(position, removed);
            }
            case EMPTY -> forget(position, removed);
            case MISALIGNED -> {
                removeMember(position, removed);
                misaligned.add(position);
            }
            case STORAGE, INPUT, OUTPUT, PRODUCTION -> {
                misaligned.remove(position);
                LocationKind kind = result.kind().orElseThrow();
                LocationKind existing = members.get(position);
                if (existing == kind)
                    return;
                removeMember(position, removed);
                addMember(position, kind);
                added.add(new LocationRecord(position, kind));
            }
        }
    }

    private void forget(RackPosition position, List<LocationRecord> removed) {
        removeMember(position, removed);
        misaligned.remove(position);
    }

    private void addMember(RackPosition position, LocationKind kind) {
        members.put(position, kind);
        switch (kind) {
            case STORAGE -> storage.add(position);
            case INPUT -> inputs++;
            case OUTPUT -> outputs++;
            case PRODUCTION -> productions++;
        }
    }

    private void removeMember(RackPosition position, List<LocationRecord> removed) {
        LocationKind kind = members.remove(position);
        if (kind == null)
            return;
        switch (kind) {
            case STORAGE -> storage.remove(position);
            case INPUT -> inputs--;
            case OUTPUT -> outputs--;
            case PRODUCTION -> productions--;
        }
        removed.add(new LocationRecord(position, kind));
    }

    // --- persistence ---------------------------------------------------------------------------------------------

    /**
     * Replaces the whole list with persisted state and schedules a full scan that keeps unloaded positions. A position
     * listed as a member and as misaligned counts as a member; for duplicate records the last one wins.
     */
    public void restore(Collection<LocationRecord> records, Collection<RackPosition> misalignedPositions) {
        Objects.requireNonNull(records, "records");
        Objects.requireNonNull(misalignedPositions, "misalignedPositions");
        clearState();
        for (LocationRecord record : records) {
            removeMember(record.position(), new ArrayList<>());
            addMember(record.position(), record.kind());
        }
        for (RackPosition position : misalignedPositions) {
            if (!members.containsKey(Objects.requireNonNull(position, "misaligned position")))
                misaligned.add(position);
        }
        markAllDirty();
    }

    /**
     * Removes every record and misaligned flag (the aisle is gone) and clears the dirty state.
     *
     * @return the removed records, in {@link RackPosition#ORDER}
     */
    public List<LocationRecord> clear() {
        List<LocationRecord> removed = records();
        clearState();
        return removed;
    }

    private void clearState() {
        members.clear();
        storage.clear();
        misaligned.clear();
        dirtyPositions.clear();
        inputs = 0;
        outputs = 0;
        productions = 0;
        fullScanPending = false;
        dropUnverified = false;
        roundRobinCursor = null;
    }

    // --- queries -------------------------------------------------------------------------------------------------

    /** All records in {@link RackPosition#ORDER} (unmodifiable copy). */
    public List<LocationRecord> records() {
        List<LocationRecord> result = new ArrayList<>(members.size());
        for (Map.Entry<RackPosition, LocationKind> entry : members.entrySet())
            result.add(new LocationRecord(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableList(result);
    }

    /** The records of one kind in {@link RackPosition#ORDER} (unmodifiable copy). */
    public List<LocationRecord> records(LocationKind kind) {
        Objects.requireNonNull(kind, "kind");
        List<LocationRecord> result = new ArrayList<>();
        for (Map.Entry<RackPosition, LocationKind> entry : members.entrySet()) {
            if (entry.getValue() == kind)
                result.add(new LocationRecord(entry.getKey(), kind));
        }
        return Collections.unmodifiableList(result);
    }

    /** The member kind at {@code position}, or empty. */
    public Optional<LocationKind> kindAt(RackPosition position) {
        return Optional.ofNullable(members.get(Objects.requireNonNull(position, "position")));
    }

    /** The misaligned positions in {@link RackPosition#ORDER} (unmodifiable view). */
    public NavigableSet<RackPosition> misalignedPositions() {
        return Collections.unmodifiableNavigableSet(misaligned);
    }

    public boolean isMisaligned(RackPosition position) {
        return misaligned.contains(Objects.requireNonNull(position, "position"));
    }

    public int memberCount() {
        return members.size();
    }

    public int storageCount() {
        return storage.size();
    }

    public int inputCount() {
        return inputs;
    }

    public int outputCount() {
        return outputs;
    }

    /** Aligned warehouse production stations ({@code docs/warehouse-system.md} §3.5). */
    public int productionCount() {
        return productions;
    }

    public int misalignedCount() {
        return misaligned.size();
    }

    /**
     * The next storage location for round-robin reconciliation: the one after the previously returned location in
     * {@link RackPosition#ORDER}, wrapping around; empty without storage locations. Removed locations are skipped.
     */
    public Optional<RackPosition> nextStorageLocation() {
        if (storage.isEmpty())
            return Optional.empty();
        RackPosition next = roundRobinCursor == null ? null : storage.higher(roundRobinCursor);
        if (next == null)
            next = storage.first();
        roundRobinCursor = next;
        return Optional.of(next);
    }

    @Override
    public String toString() {
        return "AisleMembership[storage=" + storage.size() + ", inputs=" + inputs + ", outputs=" + outputs
                + ", production=" + productions + ", misaligned=" + misaligned.size() + ", dirty="
                + (fullScanPending ? "all" : dirtyPositions.size()) + "]";
    }
}
