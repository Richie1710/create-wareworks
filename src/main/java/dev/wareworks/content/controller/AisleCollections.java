package dev.wareworks.content.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.inventory.InventorySnapshot;

/**
 * What the inventories behind this aisle's <b>collecting</b> warehouse ports held the last time they were read
 * ({@code docs/warehouse-system.md} §3.2.4, M18, issue #13). Owned by one {@link WarehouseControllerBlockEntity}; server
 * thread only.
 * <p>
 * <b>Why a cache, and why not the stock index.</b> Collected items are <b>not stock until they are stored</b>: putting
 * them into {@link dev.wareworks.core.inventory.StockIndex} would make a machine's result chest part of the warehouse's
 * counts, so a terminal would offer items the crane has not fetched yet and a stock rule would count them towards its
 * maximum. This is therefore a cache of its own, read through the same throttled {@link
 * dev.wareworks.core.inventory.SnapshotQueue} machinery under the same per-tick budget, and it answers exactly one
 * question: what may a collect job plan for, right now, without touching the world.
 * <p>
 * <b>Nothing here is persisted.</b> Like the port policies and the store filters, an entry is derived from a block that
 * may be in an unloaded chunk, so after a world load the map starts empty — and "not read yet" is <b>not</b> "empty
 * inventory": an unread port simply collects nothing until its first read, which is the safe direction (the M8 lesson,
 * {@link AisleFilters}). {@link #isStale} is what the controller's port pass uses to get that first read and every later
 * one; a hint from the block ({@link WarehouseRegistry#contentChanged}) queues an urgent one in between.
 * <p>
 * Entries are kept in {@link RackPosition#ORDER}, so the collect round robin walks the same list before and after a
 * reload.
 */
final class AisleCollections {
    /**
     * One collecting port's last read.
     *
     * @param snapshot   what the attached inventory held
     * @param readTick   game time of the read, for {@link #isStale}
     * @param ownStorage the attached inventory is one this aisle already counts as a storage location, so the port is no
     *                   collect source at all and says so on its goggles (§5, guard 3)
     * @param identity   the identity of the inventory that was read — the same {@code InventoryIdentifier} the stock
     *                   index resolves, so two ports pointed at one double chest share it — or {@code null} when it could
     *                   not be resolved. It is what makes {@link #sharing} a map walk instead of a world lookup
     */
    private record Entry(InventorySnapshot<ItemKey> snapshot, long readTick, boolean ownStorage,
                         @Nullable Object identity) {
        Entry {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    private final Map<RackPosition, Entry> reads = new TreeMap<>(RackPosition.ORDER);

    /** Stores a fresh read of the inventory behind the collecting port at {@code rack}. */
    void set(RackPosition rack, InventorySnapshot<ItemKey> snapshot, long now, boolean ownStorage,
            @Nullable Object identity) {
        Objects.requireNonNull(rack, "rack");
        Objects.requireNonNull(snapshot, "snapshot");
        reads.put(rack, new Entry(snapshot, now, ownStorage, identity));
    }

    /** The port at {@code rack} left the aisle, or no longer collects. */
    void remove(RackPosition rack) {
        reads.remove(Objects.requireNonNull(rack, "rack"));
    }

    /** The aisle is gone or was replaced. */
    void clear() {
        reads.clear();
    }

    /** Whether nothing was ever read (every aisle without a collecting port, and every aisle right after a load). */
    boolean isEmpty() {
        return reads.isEmpty();
    }

    /** Whether the inventory behind the port at {@code rack} was read into this cache at all. */
    boolean isRead(RackPosition rack) {
        return reads.containsKey(Objects.requireNonNull(rack, "rack"));
    }

    /**
     * What the port at {@code rack} may hand out, as of its last read; {@link InventorySnapshot#empty()} for a port this
     * cache has not read — "not read" never means "collect".
     */
    InventorySnapshot<ItemKey> snapshotOf(RackPosition rack) {
        Entry entry = reads.get(Objects.requireNonNull(rack, "rack"));
        return entry == null ? InventorySnapshot.empty() : entry.snapshot();
    }

    /**
     * Whether the last read of {@code rack} found anything the port may <b>fetch</b> (and it is a usable source).
     * <p>
     * {@code filter} is the one item the port's filter names, or {@code null} for a port that takes anything. Applying it
     * here is what keeps a filtered port out of the planner while its machine holds only items it does not name: without
     * it such a port would be handed to every planning run, spend a live extract on a certain refusal and report a reason
     * about a warehouse that is not at fault (M18 review). One map lookup, because a port's filter is a single key.
     */
    boolean hasItems(RackPosition rack, @Nullable ItemKey filter) {
        Entry entry = reads.get(Objects.requireNonNull(rack, "rack"));
        if (entry == null || entry.ownStorage() || entry.snapshot().isEmpty())
            return false;
        return filter == null || entry.snapshot().count(filter) > 0L;
    }

    /**
     * Items the last read of {@code rack} found that the port may fetch, for its goggles; 0 for an unread port.
     * <p>
     * Filtered exactly as {@link #hasItems} is, so the port's "Ready: N" line can never contradict the "Collects: X" line
     * above it by counting items the port will never take (M18 review).
     */
    long totalAt(RackPosition rack, @Nullable ItemKey filter) {
        Entry entry = reads.get(Objects.requireNonNull(rack, "rack"));
        if (entry == null)
            return 0L;
        return filter == null ? entry.snapshot().totalItems() : entry.snapshot().count(filter);
    }

    /**
     * Every port whose last read was of the <b>same inventory</b> as {@code rack}'s, {@code rack} itself included.
     * <p>
     * Two collecting ports may be pointed at one inventory — two faces of a machine, or the two halves of a double chest —
     * and the crane emptying it through one of them makes the other one's snapshot wrong at that very moment. The
     * identity was resolved when the snapshot was read, so answering this costs a walk over this aisle's collecting ports
     * and no world access at all (M18 review).
     */
    List<RackPosition> sharing(RackPosition rack) {
        Entry entry = reads.get(Objects.requireNonNull(rack, "rack"));
        if (entry == null)
            return List.of();
        if (entry.identity() == null)
            return List.of(rack);
        List<RackPosition> found = new ArrayList<>(2);
        for (Map.Entry<RackPosition, Entry> other : reads.entrySet()) {
            if (entry.identity().equals(other.getValue().identity()))
                found.add(other.getKey());
        }
        return found.isEmpty() ? List.of(rack) : found;
    }

    /** Whether the inventory behind {@code rack} is one this aisle already counts (§5, guard 3). */
    boolean isOwnStorage(RackPosition rack) {
        Entry entry = reads.get(Objects.requireNonNull(rack, "rack"));
        return entry != null && entry.ownStorage();
    }

    /**
     * Whether the port at {@code rack} has to be read again: never read, or read longer than {@code interval} ticks ago.
     * <p>
     * This is what covers machines that change their inventory <b>without</b> firing a neighbour-change hint — a furnace's
     * result slot, several Create blocks — at the cost of one bounded snapshot read per port per interval, instead of the
     * per-tick scan the hard rules forbid.
     */
    boolean isStale(RackPosition rack, long now, long interval) {
        Entry entry = reads.get(Objects.requireNonNull(rack, "rack"));
        return entry == null || now - entry.readTick() >= Math.max(1L, interval);
    }

    @Override
    public String toString() {
        return "AisleCollections[" + reads.size() + "]";
    }
}
