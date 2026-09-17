package dev.wareworks.core.warehouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.AisleGeometry;
import dev.wareworks.core.address.RackPosition;
import dev.wareworks.core.address.Side;

class AisleMembershipTest {
    /** 4 positions x 2 levels x 2 sides = 16 rack positions. */
    private static final AisleGeometry GEOMETRY = AisleGeometry.of(3, 2);

    private static final RackPosition OUTPUT_0L = rack(0, 0, Side.LEFT);
    private static final RackPosition INPUT_0R = rack(0, 0, Side.RIGHT);
    private static final RackPosition STORAGE_1L = rack(1, 0, Side.LEFT);
    private static final RackPosition STORAGE_2R = rack(2, 1, Side.RIGHT);
    private static final RackPosition MISALIGNED_3L = rack(3, 1, Side.LEFT);
    private static final RackPosition FREE_3R = rack(3, 0, Side.RIGHT);

    private static RackPosition rack(int x, int y, Side side) {
        return new RackPosition(x, y, side);
    }

    /** Stand-in for the world: a probe result per position (EMPTY by default) and a log of probed positions. */
    private static final class FakeAisle implements Function<RackPosition, RackProbe> {
        final Map<RackPosition, RackProbe> world = new HashMap<>();
        final List<RackPosition> probed = new ArrayList<>();

        @Override
        public RackProbe apply(RackPosition position) {
            probed.add(position);
            return world.getOrDefault(position, RackProbe.EMPTY);
        }

        FakeAisle set(RackPosition position, RackProbe probe) {
            world.put(position, probe);
            return this;
        }

        List<RackPosition> takeProbed() {
            List<RackPosition> result = List.copyOf(probed);
            probed.clear();
            return result;
        }
    }

    private static FakeAisle standardAisle() {
        return new FakeAisle()
                .set(OUTPUT_0L, RackProbe.OUTPUT)
                .set(INPUT_0R, RackProbe.INPUT)
                .set(STORAGE_1L, RackProbe.STORAGE)
                .set(STORAGE_2R, RackProbe.STORAGE)
                .set(MISALIGNED_3L, RackProbe.MISALIGNED);
    }

    private static AisleMembership scanned(FakeAisle aisle) {
        AisleMembership membership = new AisleMembership();
        membership.markAllDirty();
        membership.reconcile(GEOMETRY, aisle);
        aisle.takeProbed();
        return membership;
    }

    @Test
    void fullScanProbesEveryRackPositionOnceInOrder() {
        AisleMembership membership = new AisleMembership();
        FakeAisle aisle = standardAisle();
        assertFalse(membership.isDirty(), "a new list has nothing to do");
        assertEquals(MembershipChanges.none(), membership.reconcile(GEOMETRY, aisle));
        assertTrue(aisle.probed.isEmpty(), "no probes while clean");

        membership.markAllDirty();
        assertTrue(membership.isFullScanPending());
        MembershipChanges changes = membership.reconcile(GEOMETRY, aisle);
        assertEquals(GEOMETRY.rackPositions(), aisle.takeProbed(), "every rack position once, in order");
        assertEquals(List.of(LocationRecord.of(OUTPUT_0L, LocationKind.OUTPUT),
                LocationRecord.of(INPUT_0R, LocationKind.INPUT),
                LocationRecord.of(STORAGE_1L, LocationKind.STORAGE),
                LocationRecord.of(STORAGE_2R, LocationKind.STORAGE)), changes.added());
        assertTrue(changes.removed().isEmpty());
        assertEquals(2, membership.storageCount());
        assertEquals(1, membership.inputCount());
        assertEquals(1, membership.outputCount());
        assertEquals(1, membership.misalignedCount());
        assertEquals(4, membership.memberCount());
        assertTrue(membership.isMisaligned(MISALIGNED_3L));
        assertEquals(Optional.of(LocationKind.STORAGE), membership.kindAt(STORAGE_2R));
        assertEquals(Optional.empty(), membership.kindAt(FREE_3R));
        assertEquals(List.of(LocationRecord.of(STORAGE_1L, LocationKind.STORAGE),
                LocationRecord.of(STORAGE_2R, LocationKind.STORAGE)), membership.records(LocationKind.STORAGE));
        assertFalse(membership.isDirty());

        assertTrue(membership.reconcile(GEOMETRY, aisle).isEmpty(), "a clean list does nothing");
        membership.markAllDirty();
        assertTrue(membership.reconcile(GEOMETRY, aisle).isEmpty(), "an unchanged world yields no changes");
    }

    @Test
    void dirtyPositionsProbeOnlyThosePositions() {
        FakeAisle aisle = standardAisle();
        AisleMembership membership = scanned(aisle);

        aisle.set(STORAGE_1L, RackProbe.EMPTY).set(FREE_3R, RackProbe.STORAGE);
        membership.markDirty(FREE_3R);
        membership.markDirty(STORAGE_1L);
        membership.markDirty(STORAGE_1L);
        assertFalse(membership.isFullScanPending());
        MembershipChanges changes = membership.reconcile(GEOMETRY, aisle);
        assertEquals(List.of(STORAGE_1L, FREE_3R), aisle.takeProbed(), "only dirty positions, each once, in order");
        assertEquals(List.of(LocationRecord.of(FREE_3R, LocationKind.STORAGE)), changes.added());
        assertEquals(List.of(LocationRecord.of(STORAGE_1L, LocationKind.STORAGE)), changes.removed());

        aisle.set(STORAGE_2R, RackProbe.MISALIGNED);
        membership.markDirty(STORAGE_2R);
        changes = membership.reconcile(GEOMETRY, aisle);
        assertEquals(List.of(LocationRecord.of(STORAGE_2R, LocationKind.STORAGE)), changes.removed(), "rotated away");
        assertTrue(changes.added().isEmpty());
        assertEquals(2, membership.misalignedCount());

        aisle.set(INPUT_0R, RackProbe.OUTPUT);
        membership.markDirty(INPUT_0R);
        changes = membership.reconcile(GEOMETRY, aisle);
        assertEquals(List.of(LocationRecord.of(INPUT_0R, LocationKind.INPUT)), changes.removed(), "old kind removed");
        assertEquals(List.of(LocationRecord.of(INPUT_0R, LocationKind.OUTPUT)), changes.added(), "new kind added");
        assertEquals(0, membership.inputCount());
        assertEquals(2, membership.outputCount());

        aisle.set(MISALIGNED_3L, RackProbe.EMPTY).set(STORAGE_2R, RackProbe.STORAGE);
        membership.markDirty(MISALIGNED_3L);
        membership.markDirty(STORAGE_2R);
        changes = membership.reconcile(GEOMETRY, aisle);
        assertEquals(List.of(LocationRecord.of(STORAGE_2R, LocationKind.STORAGE)), changes.added(), "fixed facing");
        assertEquals(0, membership.misalignedCount(), "broken and fixed misaligned blocks are forgotten");
    }

    /**
     * The probe is allowed to change the world and therefore to mark the position it is probing dirty again: the
     * warehouse terminal's intake port is written from inside it ({@code WarehouseMember#alignToAisle}), and that write
     * notifies the controller back. The pass must survive the re-entry and must <b>keep</b> the mark for the next pass
     * instead of losing it or probing twice, which is why {@link AisleMembership#reconcile} snapshots and clears the
     * dirty set before its loop. Pins the contract the controller's probe relies on (M10 review).
     */
    @Test
    void aProbeMayMarkTheProbedPositionDirtyAgain() {
        FakeAisle aisle = standardAisle();
        AisleMembership membership = scanned(aisle);
        membership.markDirty(STORAGE_1L);

        MembershipChanges changes = membership.reconcile(GEOMETRY, position -> {
            membership.markDirty(position); // what a block-state write from inside the probe amounts to
            return aisle.apply(position);
        });
        assertEquals(List.of(STORAGE_1L), aisle.takeProbed(), "the re-mark does not probe the position twice");
        assertTrue(changes.isEmpty(), "the record did not change, so nothing is reported");
        assertTrue(membership.isDirty(), "the mark raised inside the probe survives the pass");
        assertFalse(membership.isFullScanPending(), "and it stays a single dirty position");

        // The next pass finds a settled world, writes nothing and therefore marks nothing: the list goes quiet.
        assertTrue(membership.reconcile(GEOMETRY, aisle).isEmpty());
        assertEquals(List.of(STORAGE_1L), aisle.takeProbed(), "exactly the position that was marked again");
        assertFalse(membership.isDirty(), "a settled aisle stops re-probing");
        assertEquals(Optional.of(LocationKind.STORAGE), membership.kindAt(STORAGE_1L), "the record is unchanged");
    }

    @Test
    void tooManyDirtyPositionsEscalateToAFullScan() {
        FakeAisle aisle = standardAisle();
        AisleMembership membership = new AisleMembership(2);
        membership.markDirty(STORAGE_1L);
        membership.markDirty(STORAGE_2R);
        assertFalse(membership.isFullScanPending(), "within the limit");
        membership.markDirty(FREE_3R);
        assertTrue(membership.isFullScanPending(), "over the limit");
        membership.reconcile(GEOMETRY, aisle);
        assertEquals(GEOMETRY.rackPositionCount(), aisle.takeProbed().size());
        assertEquals(2, new AisleMembership(2).maxDirtyPositions());
        assertEquals(AisleMembership.DEFAULT_MAX_DIRTY_POSITIONS, new AisleMembership().maxDirtyPositions());
    }

    @Test
    void unloadedPositionsKeepWhatIsKnownUntilInvalidated() {
        FakeAisle aisle = standardAisle();
        AisleMembership membership = scanned(aisle);
        aisle.set(STORAGE_1L, RackProbe.UNLOADED).set(MISALIGNED_3L, RackProbe.UNLOADED);

        membership.markAllDirty();
        assertTrue(membership.reconcile(GEOMETRY, aisle).isEmpty(), "full scan keeps unloaded records");
        membership.markDirty(STORAGE_1L);
        assertTrue(membership.reconcile(GEOMETRY, aisle).isEmpty(), "single probe keeps an unloaded record");
        assertEquals(2, membership.storageCount());
        assertEquals(1, membership.misalignedCount());

        membership.invalidateAll();
        MembershipChanges changes = membership.reconcile(GEOMETRY, aisle);
        assertEquals(List.of(LocationRecord.of(STORAGE_1L, LocationKind.STORAGE)), changes.removed(),
                "an invalidated list forgets what it cannot verify");
        assertEquals(0, membership.misalignedCount());

        aisle.set(STORAGE_1L, RackProbe.STORAGE);
        membership.markAllDirty();
        assertEquals(List.of(LocationRecord.of(STORAGE_1L, LocationKind.STORAGE)),
                membership.reconcile(GEOMETRY, aisle).added(), "loaded again: found again");
        aisle.set(STORAGE_1L, RackProbe.UNLOADED);
        membership.markAllDirty();
        assertTrue(membership.reconcile(GEOMETRY, aisle).isEmpty(), "invalidation applies to one scan only");
    }

    @Test
    void shrinkingGeometryRemovesRecordsOutside() {
        FakeAisle aisle = standardAisle();
        AisleMembership membership = scanned(aisle);
        AisleGeometry smaller = AisleGeometry.of(1, 1);

        membership.markAllDirty();
        MembershipChanges changes = membership.reconcile(smaller, aisle);
        assertEquals(smaller.rackPositions(), aisle.takeProbed(), "only positions of the new geometry are probed");
        assertEquals(List.of(LocationRecord.of(STORAGE_2R, LocationKind.STORAGE)), changes.removed());
        assertEquals(0, membership.misalignedCount(), "misaligned outside forgotten");
        assertEquals(3, membership.memberCount());

        membership.markDirty(rack(5, 0, Side.LEFT));
        assertTrue(membership.reconcile(smaller, aisle).isEmpty(), "a dirty position outside is not probed");
        assertTrue(aisle.takeProbed().isEmpty());
    }

    @Test
    void restoreReplacesTheListAndSchedulesAFullScan() {
        AisleMembership membership = scanned(standardAisle());
        membership.restore(List.of(LocationRecord.of(STORAGE_1L, LocationKind.STORAGE),
                        LocationRecord.of(INPUT_0R, LocationKind.INPUT),
                        LocationRecord.of(STORAGE_1L, LocationKind.OUTPUT)),
                List.of(FREE_3R, STORAGE_1L));
        assertEquals(Optional.of(LocationKind.OUTPUT), membership.kindAt(STORAGE_1L), "the last duplicate wins");
        assertEquals(0, membership.storageCount());
        assertEquals(1, membership.inputCount());
        assertEquals(1, membership.outputCount());
        assertEquals(List.of(FREE_3R), List.copyOf(membership.misalignedPositions()), "members are never misaligned");
        assertTrue(membership.isFullScanPending());

        FakeAisle unloaded = new FakeAisle();
        for (RackPosition position : GEOMETRY.rackPositions())
            unloaded.set(position, RackProbe.UNLOADED);
        assertTrue(membership.reconcile(GEOMETRY, unloaded).isEmpty(), "restored records survive unloaded positions");
        assertEquals(2, membership.memberCount());
    }

    @Test
    void clearReturnsAllRecords() {
        AisleMembership membership = scanned(standardAisle());
        membership.markDirty(FREE_3R);
        List<LocationRecord> removed = membership.clear();
        assertEquals(4, removed.size());
        assertEquals(OUTPUT_0L, removed.getFirst().position());
        assertEquals(0, membership.memberCount());
        assertEquals(0, membership.misalignedCount());
        assertEquals(0, membership.inputCount());
        assertFalse(membership.isDirty());
        assertEquals(Optional.empty(), membership.nextStorageLocation());
    }

    @Test
    void roundRobinCyclesOverStorageLocations() {
        FakeAisle aisle = standardAisle().set(FREE_3R, RackProbe.STORAGE);
        AisleMembership membership = scanned(aisle);
        assertEquals(Optional.of(STORAGE_1L), membership.nextStorageLocation());
        assertEquals(Optional.of(STORAGE_2R), membership.nextStorageLocation(), "stations are skipped");
        assertEquals(Optional.of(FREE_3R), membership.nextStorageLocation());
        assertEquals(Optional.of(STORAGE_1L), membership.nextStorageLocation(), "wraps around");

        aisle.set(STORAGE_2R, RackProbe.EMPTY);
        membership.markDirty(STORAGE_2R);
        membership.reconcile(GEOMETRY, aisle);
        assertEquals(Optional.of(FREE_3R), membership.nextStorageLocation(), "removed locations are skipped");
        assertEquals(Optional.empty(), new AisleMembership().nextStorageLocation());
    }

    @Test
    void kindsAndProbes() {
        assertTrue(LocationKind.STORAGE.facesAwayFromAisle());
        assertFalse(LocationKind.INPUT.facesAwayFromAisle());
        assertFalse(LocationKind.OUTPUT.facesAwayFromAisle());
        assertFalse(LocationKind.STORAGE.isStation());
        assertTrue(LocationKind.OUTPUT.isStation());
        for (LocationKind kind : LocationKind.values()) {
            assertEquals(Optional.of(kind), LocationKind.byName(kind.name()));
            assertEquals(Optional.of(kind), RackProbe.member(kind).kind());
        }
        assertEquals(Optional.empty(), LocationKind.byName("storage"), "save names are case-sensitive");
        assertEquals(Optional.empty(), LocationKind.byName(null));
        assertEquals(Optional.empty(), RackProbe.UNLOADED.kind());
        assertEquals(Optional.empty(), RackProbe.MISALIGNED.kind());
        LocationRecord record = LocationRecord.of(STORAGE_2R, LocationKind.STORAGE);
        assertEquals(2, record.x());
        assertEquals(1, record.y());
        assertEquals(Side.RIGHT, record.side());
    }

    @Test
    void invalidArgumentsAreRejected() {
        AisleMembership membership = new AisleMembership();
        assertThrows(IllegalArgumentException.class, () -> new AisleMembership(-1));
        assertThrows(NullPointerException.class, () -> membership.markDirty(null));
        assertThrows(NullPointerException.class, () -> membership.reconcile(null, position -> RackProbe.EMPTY));
        assertThrows(NullPointerException.class, () -> membership.reconcile(GEOMETRY, null));
        membership.markAllDirty();
        assertThrows(NullPointerException.class, () -> membership.reconcile(GEOMETRY, position -> null));
        assertThrows(NullPointerException.class, () -> LocationRecord.of(null, LocationKind.STORAGE));
        assertThrows(NullPointerException.class, () -> LocationRecord.of(STORAGE_1L, null));
        assertThrows(NullPointerException.class, () -> RackProbe.member(null));
        assertThrows(UnsupportedOperationException.class, () -> membership.records().clear());
        assertThrows(UnsupportedOperationException.class, () -> membership.misalignedPositions().clear());
    }
}
