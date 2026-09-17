package dev.wareworks.core.address;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

class StorageAddressTest {
    private static final int[] LEVELS = {1, 2, 9, 10, 42, 99, 100, 128, 999};
    private static final int[] POSITIONS = {0, 1, 7, 9, 10, 32, 99, 100, 128, 999};

    @Test
    void formatsTheDesignExample() {
        StorageAddress address = new StorageAddress('A', 3, 7, Side.RIGHT);
        assertEquals("A-03-07R", address.format());
        assertEquals("A-03-07R", address.toString());
        assertEquals("Z-01-00L", new StorageAddress('Z', 1, 0, Side.LEFT).format());
        assertEquals("B-100-128L", new StorageAddress('B', 100, 128, Side.LEFT).format(), "three digits stay readable");
        assertEquals("C-999-999R", new StorageAddress('C', 999, 999, Side.RIGHT).format());
    }

    @Test
    void derivesFromRackPosition() {
        RackPosition rack = new RackPosition(7, 2, Side.RIGHT);
        StorageAddress address = StorageAddress.of('A', rack);
        assertEquals("A-03-07R", address.format(), "level = y + 1, position = x");
        assertEquals(rack, address.rackPosition());
        assertEquals(new RackPosition(0, 0, Side.LEFT), StorageAddress.parse("D-01-00L").rackPosition());
        assertThrows(IllegalArgumentException.class,
                () -> StorageAddress.of('A', new RackPosition(0, StorageAddress.MAX_LEVEL, Side.LEFT)),
                "level index beyond the address format");
        assertThrows(IllegalArgumentException.class,
                () -> StorageAddress.of('A', new RackPosition(StorageAddress.MAX_POSITION + 1, 0, Side.LEFT)));
        assertThrows(IllegalArgumentException.class, () -> StorageAddress.of('a', rack));
        assertThrows(NullPointerException.class, () -> StorageAddress.of('A', null));
    }

    @Test
    void formatParseRoundTripForAllAislesAndSides() {
        Set<String> seen = new HashSet<>();
        for (int aisle = 0; aisle < StorageAddress.AISLE_COUNT; aisle++) {
            char letter = StorageAddress.aisleLetter(aisle);
            for (int level : LEVELS) {
                for (int position : POSITIONS) {
                    for (Side side : Side.values()) {
                        StorageAddress address = new StorageAddress(letter, level, position, side);
                        String text = address.format();
                        assertEquals(address, StorageAddress.parse(text), text);
                        assertEquals(Optional.of(address), StorageAddress.tryParse(text), text);
                        assertEquals(address, StorageAddress.of(letter, address.rackPosition()), text);
                        assertTrue(seen.add(text), "formats must be unique: " + text);
                    }
                }
            }
        }
    }

    @Test
    void rejectsInvalidText() {
        List<String> invalid = new ArrayList<>(List.of(
                "", " ", "A", "A-03-07", "A-03-R", "A-03-07RR", "AA-03-07R", "A-03-07-R",
                "a-03-07R", "A-03-07r", "A-03-07X", "@-03-07R", "[-03-07R", "Ä-03-07R",
                "A-3-07R", "A-03-7R", "A-3-7R", "A_03_07R", "A 03 07R", "A--3-07R", "A-+3-07R", "A-03--7R",
                " A-03-07R", "A-03-07R ", "A-03-07R\n", "\tA-03-07R",
                "A-00-07R", "A-1000-07R", "A-03-1000R", "A-0003-07R",
                "A-003-07R", "A-03-007R", "A-099-07R", "A-03-000R",
                "A-٣٣-07R", "A-０３-07R", "A-03-07ʀ"));
        for (String text : invalid) {
            assertThrows(IllegalArgumentException.class, () -> StorageAddress.parse(text), "must reject \"" + text + "\"");
            assertEquals(Optional.empty(), StorageAddress.tryParse(text), "tryParse must reject \"" + text + "\"");
        }
        assertThrows(NullPointerException.class, () -> StorageAddress.parse(null));
        assertEquals(Optional.empty(), StorageAddress.tryParse(null));
    }

    @Test
    void acceptsBoundaryValues() {
        assertEquals(new StorageAddress('A', 1, 0, Side.LEFT), StorageAddress.parse("A-01-00L"));
        assertEquals(new StorageAddress('Z', 999, 999, Side.RIGHT), StorageAddress.parse("Z-999-999R"));
        assertEquals(new StorageAddress('M', 99, 100, Side.LEFT), StorageAddress.parse("M-99-100L"));
    }

    @Test
    void validatesComponents() {
        assertThrows(IllegalArgumentException.class, () -> new StorageAddress('@', 1, 0, Side.LEFT));
        assertThrows(IllegalArgumentException.class, () -> new StorageAddress('[', 1, 0, Side.LEFT));
        assertThrows(IllegalArgumentException.class, () -> new StorageAddress('a', 1, 0, Side.LEFT));
        assertThrows(IllegalArgumentException.class, () -> new StorageAddress('1', 1, 0, Side.LEFT));
        assertThrows(IllegalArgumentException.class, () -> new StorageAddress('A', 0, 0, Side.LEFT));
        assertThrows(IllegalArgumentException.class, () -> new StorageAddress('A', -1, 0, Side.LEFT));
        assertThrows(IllegalArgumentException.class, () -> new StorageAddress('A', 1000, 0, Side.LEFT));
        assertThrows(IllegalArgumentException.class, () -> new StorageAddress('A', 1, -1, Side.LEFT));
        assertThrows(IllegalArgumentException.class, () -> new StorageAddress('A', 1, 1000, Side.LEFT));
        assertThrows(NullPointerException.class, () -> new StorageAddress('A', 1, 0, null));
    }

    @Test
    void aisleLettersAndIndices() {
        assertEquals('A', StorageAddress.aisleLetter(0));
        assertEquals('Z', StorageAddress.aisleLetter(25));
        assertEquals(26, StorageAddress.AISLE_COUNT);
        assertThrows(IllegalArgumentException.class, () -> StorageAddress.aisleLetter(-1));
        assertThrows(IllegalArgumentException.class, () -> StorageAddress.aisleLetter(26));
        for (int i = 0; i < StorageAddress.AISLE_COUNT; i++)
            assertEquals(i, new StorageAddress(StorageAddress.aisleLetter(i), 1, 0, Side.LEFT).aisleIndex());
        assertTrue(StorageAddress.isValidAisle('A'));
        assertTrue(StorageAddress.isValidAisle('Z'));
        assertEquals(false, StorageAddress.isValidAisle('a'));
    }

    @Test
    void equalityIsByValue() {
        assertEquals(StorageAddress.parse("A-03-07R"), new StorageAddress('A', 3, 7, Side.RIGHT));
        assertNotEquals(StorageAddress.parse("A-03-07R"), StorageAddress.parse("A-03-07L"), "sides differ");
        assertNotEquals(StorageAddress.parse("A-03-07R"), StorageAddress.parse("B-03-07R"), "aisles differ");
    }

    @Test
    void sideLettersAndOffsets() {
        assertEquals('L', Side.LEFT.letter());
        assertEquals('R', Side.RIGHT.letter());
        assertEquals(Optional.of(Side.LEFT), Side.fromLetter('L'));
        assertEquals(Optional.of(Side.RIGHT), Side.fromLetter('R'));
        assertEquals(Optional.empty(), Side.fromLetter('l'));
        assertEquals(Optional.empty(), Side.fromLetter('X'));
        assertEquals(Side.RIGHT, Side.LEFT.opposite());
        assertEquals(Side.LEFT, Side.RIGHT.opposite());
        assertEquals(-1, Side.LEFT.lateralOffset());
        assertEquals(1, Side.RIGHT.lateralOffset());
        for (Side side : Side.values())
            assertEquals(Optional.of(side), Side.fromLateralOffset(side.lateralOffset()));
        assertEquals(Optional.empty(), Side.fromLateralOffset(0), "the aisle line has no side");
        assertEquals(Optional.empty(), Side.fromLateralOffset(2));
        assertEquals(Optional.empty(), Side.fromLateralOffset(-2));
    }

    @Test
    void rackPositionValidationAndOrder() {
        assertThrows(IllegalArgumentException.class, () -> new RackPosition(-1, 0, Side.LEFT));
        assertThrows(IllegalArgumentException.class, () -> new RackPosition(0, -1, Side.LEFT));
        assertThrows(NullPointerException.class, () -> new RackPosition(0, 0, null));
        List<RackPosition> sorted = new ArrayList<>(List.of(
                RackPosition.of(1, 0, Side.LEFT), RackPosition.of(0, 1, Side.RIGHT), RackPosition.of(0, 1, Side.LEFT),
                RackPosition.of(0, 0, Side.RIGHT), RackPosition.of(0, 0, Side.LEFT)));
        sorted.sort(null);
        assertEquals(List.of(RackPosition.of(0, 0, Side.LEFT), RackPosition.of(0, 0, Side.RIGHT),
                RackPosition.of(0, 1, Side.LEFT), RackPosition.of(0, 1, Side.RIGHT), RackPosition.of(1, 0, Side.LEFT)),
                sorted, "position, then level, then LEFT before RIGHT");
    }
}
