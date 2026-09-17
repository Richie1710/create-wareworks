package dev.wareworks.core.address;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class AisleGeometryTest {
    private static final int LENGTH = 5;
    private static final int HEIGHT = 3;
    private static final int MARGIN = 2;

    @Test
    void validatesBounds() {
        assertThrows(IllegalArgumentException.class, () -> new AisleGeometry(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new AisleGeometry(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new AisleGeometry(0, -4));
        assertThrows(IllegalArgumentException.class, () -> new AisleGeometry(AisleGeometry.MAX_LENGTH + 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new AisleGeometry(0, AisleGeometry.MAX_HEIGHT + 1));
        new AisleGeometry(0, AisleGeometry.MIN_HEIGHT);
        new AisleGeometry(AisleGeometry.MAX_LENGTH, AisleGeometry.MAX_HEIGHT);
    }

    @Test
    void countsPositions() {
        AisleGeometry dockOnly = AisleGeometry.of(0, 1);
        assertEquals(1, dockOnly.positionCount());
        assertEquals(2, dockOnly.rackPositionCount(), "a dock without rails still has one rack position per side");

        AisleGeometry geometry = AisleGeometry.of(LENGTH, HEIGHT);
        assertEquals(LENGTH + 1, geometry.positionCount());
        assertEquals(2 * (LENGTH + 1) * HEIGHT, geometry.rackPositionCount());

        AisleGeometry largest = AisleGeometry.of(AisleGeometry.MAX_LENGTH, AisleGeometry.MAX_HEIGHT);
        assertEquals(2 * (AisleGeometry.MAX_LENGTH + 1) * AisleGeometry.MAX_HEIGHT, largest.rackPositionCount(),
                "no overflow at the limits");
    }

    @Test
    void containsExactlyTheAisleRange() {
        AisleGeometry geometry = AisleGeometry.of(LENGTH, HEIGHT);
        for (int x = -MARGIN; x <= LENGTH + MARGIN; x++) {
            for (int y = -MARGIN; y <= HEIGHT + MARGIN; y++) {
                boolean expected = x >= 0 && x <= LENGTH && y >= 0 && y <= HEIGHT - 1;
                assertEquals(expected, geometry.contains(x, y), "contains(" + x + ", " + y + ")");
                if (x < 0 || y < 0)
                    continue;
                for (Side side : Side.values()) {
                    RackPosition rack = RackPosition.of(x, y, side);
                    assertEquals(expected, geometry.contains(rack), rack.toString());
                    assertEquals(expected, geometry.rackPositions().contains(rack), "list contains " + rack);
                    assertEquals(expected ? geometry.indexOf(rack) : -1, geometry.rackPositions().indexOf(rack));
                }
            }
        }
        assertTrue(geometry.contains(0, 0), "dock column, dock level");
        assertTrue(geometry.contains(LENGTH, HEIGHT - 1), "far end, top level");
        assertFalse(geometry.contains(LENGTH + 1, 0), "beyond the last rail");
        assertFalse(geometry.contains(0, HEIGHT), "above the mast");
    }

    @Test
    void enumeratesRackPositionsInStableOrder() {
        AisleGeometry geometry = AisleGeometry.of(LENGTH, HEIGHT);
        List<RackPosition> positions = geometry.rackPositions();
        assertEquals(geometry.rackPositionCount(), positions.size());

        Set<RackPosition> distinct = new HashSet<>(positions);
        assertEquals(positions.size(), distinct.size(), "no duplicates");
        List<RackPosition> sorted = new ArrayList<>(positions);
        sorted.sort(RackPosition.ORDER);
        assertEquals(sorted, positions, "position, then level, then side");

        for (int i = 0; i < positions.size(); i++) {
            RackPosition rack = positions.get(i);
            assertTrue(geometry.contains(rack), rack.toString());
            assertEquals(i, geometry.indexOf(rack), "indexOf inverts get");
            assertEquals(rack, geometry.rackPosition(i));
        }
        assertEquals(RackPosition.of(0, 0, Side.LEFT), positions.getFirst());
        assertEquals(RackPosition.of(0, 0, Side.RIGHT), positions.get(1));
        assertEquals(RackPosition.of(LENGTH, HEIGHT - 1, Side.RIGHT), positions.getLast());

        assertEquals(-1, geometry.indexOf(RackPosition.of(LENGTH + 1, 0, Side.LEFT)));
        assertEquals(-1, positions.indexOf("not a rack position"));
        assertThrows(IndexOutOfBoundsException.class, () -> positions.get(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> positions.get(positions.size()));
        assertThrows(UnsupportedOperationException.class, () -> positions.add(RackPosition.of(0, 0, Side.LEFT)));
        assertThrows(NullPointerException.class, () -> geometry.indexOf(null));
    }

    @Test
    void everyRackPositionHasAnAddress() {
        AisleGeometry largest = AisleGeometry.of(AisleGeometry.MAX_LENGTH, AisleGeometry.MAX_HEIGHT);
        RackPosition last = largest.rackPositions().getLast();
        assertEquals("A-999-999R", StorageAddress.of('A', last).format());
        assertEquals(last, StorageAddress.of('A', last).rackPosition());
    }

    @Test
    void withers() {
        AisleGeometry geometry = AisleGeometry.of(LENGTH, HEIGHT);
        assertEquals(AisleGeometry.of(LENGTH + 1, HEIGHT), geometry.withLength(LENGTH + 1));
        assertEquals(AisleGeometry.of(LENGTH, 1), geometry.withHeight(1));
        assertThrows(IllegalArgumentException.class, () -> geometry.withHeight(0));
    }

    @Test
    void scannedLengthOfCompleteScansIsAuthoritative() {
        int max = 32;
        assertEquals(5, AisleGeometry.scannedLength(5, false, 12, max), "rails removed: the aisle shrinks");
        assertEquals(12, AisleGeometry.scannedLength(12, false, 5, max), "rails added: the aisle grows");
        assertEquals(0, AisleGeometry.scannedLength(0, false, 7, max));
        assertEquals(max, AisleGeometry.scannedLength(max + 3, false, 0, max), "capped");
    }

    @Test
    void scannedLengthKeepsKnownRailsBehindUnloadedChunks() {
        int max = 32;
        assertEquals(12, AisleGeometry.scannedLength(4, true, 12, max), "unloaded part keeps the previous length");
        assertEquals(9, AisleGeometry.scannedLength(9, true, 3, max), "more rails found than known before");
        assertEquals(max, AisleGeometry.scannedLength(2, true, 40, max), "previous length above a lowered cap");
        assertEquals(0, AisleGeometry.scannedLength(0, true, 0, max));
        assertEquals(0, AisleGeometry.scannedLength(5, true, 9, 0), "cap zero");
    }

    @Test
    void scannedLengthRejectsNegativeInput() {
        assertThrows(IllegalArgumentException.class, () -> AisleGeometry.scannedLength(-1, false, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> AisleGeometry.scannedLength(0, true, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> AisleGeometry.scannedLength(0, false, 0, -1));
    }
}
