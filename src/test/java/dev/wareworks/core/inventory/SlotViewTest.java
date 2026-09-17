package dev.wareworks.core.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SlotViewTest {
    @Test
    void emptySlotHasNoKeyCountOrStackSize() {
        SlotView<String> slot = SlotView.empty(64);
        assertTrue(slot.isEmpty());
        assertNull(slot.key());
        assertEquals(0, slot.count());
        assertEquals(0, slot.maxStackSize());
        assertEquals(64, slot.slotLimit());
        assertFalse(slot.holds("iron"));
        assertFalse(slot.holds(null));
    }

    @Test
    void zeroCountNormalisesToEmpty() {
        SlotView<String> slot = SlotView.of("iron", 0, 99, 64);
        assertTrue(slot.isEmpty());
        assertNull(slot.key());
        assertEquals(0, slot.maxStackSize());
        assertEquals(SlotView.<String>empty(99), slot);
    }

    @Test
    void nullKeyNormalisesToEmptyEvenWithCount() {
        SlotView<String> slot = new SlotView<>(null, 12, 64, 64);
        assertTrue(slot.isEmpty());
        assertEquals(0, slot.count());
    }

    @Test
    void occupiedSlotKeepsValues() {
        SlotView<String> slot = SlotView.of("iron", 32, 99, 64);
        assertFalse(slot.isEmpty());
        assertTrue(slot.holds("iron"));
        assertFalse(slot.holds("gold"));
        assertEquals(32, slot.count());
        assertEquals(64, slot.maxStackSize());
    }

    @Test
    void countsAboveStackSizeAreAllowed() {
        SlotView<String> slot = SlotView.of("cobblestone", 4096, 8192, 64);
        assertEquals(4096, slot.count());
    }

    @Test
    void rejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> SlotView.of("iron", -1, 64, 64));
        assertThrows(IllegalArgumentException.class, () -> SlotView.of("iron", 1, -1, 64));
        assertThrows(IllegalArgumentException.class, () -> SlotView.of("iron", 1, 64, 0));
        assertThrows(IllegalArgumentException.class, () -> SlotView.empty(-5));
        assertThrows(NullPointerException.class, () -> SlotView.of(null, 1, 64, 64));
    }

    @Test
    void zeroSlotLimitIsAllowed() {
        SlotView<String> slot = SlotView.empty(0);
        assertEquals(0, slot.slotLimit());
    }
}
