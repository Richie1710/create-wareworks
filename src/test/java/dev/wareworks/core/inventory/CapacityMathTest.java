package dev.wareworks.core.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CapacityMathTest {
    private static final int STACK = 64;
    private static final int SMALL_STACK = 16;
    private static final int UNSTACKABLE = 1;
    private static final int CONTAINER_LIMIT = 99;

    @Nested
    class SlotCapacity {
        @Test
        void slotLimitAboveStackSizeIsCappedByStackSize() {
            assertEquals(STACK, CapacityMath.slotCapacity(CONTAINER_LIMIT, STACK, 0));
        }

        @Test
        void slotLimitBelowStackSizeCapsTheSlot() {
            assertEquals(10, CapacityMath.slotCapacity(10, STACK, 0));
            assertEquals(1, CapacityMath.slotCapacity(1, STACK, 1));
        }

        @Test
        void zeroSlotLimitHoldsNothing() {
            assertEquals(0, CapacityMath.slotCapacity(0, STACK, 0));
        }

        @Test
        void countAboveStackSizeSwitchesToSlotLimit() {
            // A drawer-like slot already holds more than a stack, so the handler ignores stack sizes.
            assertEquals(2048, CapacityMath.slotCapacity(2048, STACK, 1000));
        }

        @Test
        void countAtStackSizeStaysConservative() {
            assertEquals(STACK, CapacityMath.slotCapacity(2048, STACK, STACK));
        }

        @Test
        void rejectsInvalidStackSize() {
            assertThrows(IllegalArgumentException.class, () -> CapacityMath.slotCapacity(64, 0, 0));
        }
    }

    @Nested
    class Insertable {
        @Test
        void emptySlotUsesKeyStackSize() {
            assertEquals(STACK, CapacityMath.insertable(SlotView.empty(CONTAINER_LIMIT), "iron", STACK));
            assertEquals(SMALL_STACK, CapacityMath.insertable(SlotView.empty(CONTAINER_LIMIT), "pearl", SMALL_STACK));
            assertEquals(UNSTACKABLE, CapacityMath.insertable(SlotView.empty(CONTAINER_LIMIT), "sword", UNSTACKABLE));
        }

        @Test
        void emptySlotWithSmallLimit() {
            assertEquals(8, CapacityMath.insertable(SlotView.empty(8), "iron", STACK));
        }

        @Test
        void matchingSlotFillsUpToStackSize() {
            assertEquals(STACK - 40, CapacityMath.insertable(SlotView.of("iron", 40, CONTAINER_LIMIT, STACK), "iron", STACK));
        }

        @Test
        void fullMatchingSlotAcceptsNothing() {
            assertEquals(0, CapacityMath.insertable(SlotView.of("iron", STACK, CONTAINER_LIMIT, STACK), "iron", STACK));
        }

        @Test
        void otherKeyBlocksSlot() {
            assertEquals(0, CapacityMath.insertable(SlotView.of("gold", 1, CONTAINER_LIMIT, STACK), "iron", STACK));
        }

        @Test
        void slotLimitBelowStackSizeOnMatchingSlot() {
            assertEquals(6, CapacityMath.insertable(SlotView.of("iron", 4, 10, STACK), "iron", STACK));
        }

        @Test
        void inconsistentSlotAboveItsLimitAcceptsNothing() {
            assertEquals(0, CapacityMath.insertable(SlotView.of("iron", 50, 10, STACK), "iron", STACK));
            assertEquals(0, CapacityMath.insertable(SlotView.of("iron", 5000, 2048, STACK), "iron", STACK));
        }

        @Test
        void drawerLikeSlotWithHugeCount() {
            SlotView<String> drawer = SlotView.of("cobblestone", 1000, 2048, STACK);
            assertEquals(1048, CapacityMath.insertable(drawer, "cobblestone", STACK));
        }

        @Test
        void mixedSlotsAreSummed() {
            List<SlotView<String>> slots = List.of(
                    SlotView.of("iron", 60, CONTAINER_LIMIT, STACK),   // +4
                    SlotView.of("gold", 10, CONTAINER_LIMIT, STACK),   // +0
                    SlotView.empty(CONTAINER_LIMIT),                   // +64
                    SlotView.of("iron", STACK, CONTAINER_LIMIT, STACK), // +0
                    SlotView.empty(32));                               // +32
            assertEquals(4 + STACK + 32, CapacityMath.insertable(slots, "iron", STACK));
            assertEquals(STACK + 32, CapacityMath.insertable(slots, "diamond", STACK));
        }

        @Test
        void zeroSlotsAcceptNothing() {
            assertEquals(0, CapacityMath.insertable(List.<SlotView<String>>of(), "iron", STACK));
        }

        @Test
        void manyHugeSlotsDoNotOverflowInt() {
            List<SlotView<String>> slots = new ArrayList<>();
            for (int i = 0; i < 4; i++)
                slots.add(SlotView.of("cobblestone", STACK + 1, Integer.MAX_VALUE, STACK));
            long expected = 4L * ((long) Integer.MAX_VALUE - (STACK + 1));
            assertEquals(expected, CapacityMath.insertable(slots, "cobblestone", STACK));
        }

        @Test
        void rejectsInvalidArguments() {
            assertThrows(IllegalArgumentException.class, () -> CapacityMath.insertable(SlotView.empty(64), "iron", 0));
            assertThrows(NullPointerException.class, () -> CapacityMath.insertable(SlotView.<String>empty(64), null, STACK));
        }
    }

    @Nested
    class Extractable {
        @Test
        void sumsMatchingSlotsOnly() {
            List<SlotView<String>> slots = List.of(
                    SlotView.of("iron", 60, CONTAINER_LIMIT, STACK),
                    SlotView.of("gold", 10, CONTAINER_LIMIT, STACK),
                    SlotView.empty(CONTAINER_LIMIT),
                    SlotView.of("iron", 5, CONTAINER_LIMIT, STACK));
            assertEquals(65, CapacityMath.extractable(slots, "iron"));
            assertEquals(10, CapacityMath.extractable(slots, "gold"));
            assertEquals(0, CapacityMath.extractable(slots, "diamond"));
        }

        @Test
        void hugeCountsUseLongs() {
            List<SlotView<String>> slots = List.of(
                    SlotView.of("cobblestone", Integer.MAX_VALUE, Integer.MAX_VALUE, STACK),
                    SlotView.of("cobblestone", Integer.MAX_VALUE, Integer.MAX_VALUE, STACK));
            assertEquals(2L * Integer.MAX_VALUE, CapacityMath.extractable(slots, "cobblestone"));
        }

        @Test
        void zeroSlots() {
            assertEquals(0, CapacityMath.extractable(List.<SlotView<String>>of(), "iron"));
        }
    }

    @Nested
    class CarryLimit {
        @Test
        void defaultGrabberCarriesOneStack() {
            assertEquals(STACK, CapacityMath.carryLimit(STACK, 1, 64));
            assertEquals(SMALL_STACK, CapacityMath.carryLimit(SMALL_STACK, 1, 64));
            assertEquals(UNSTACKABLE, CapacityMath.carryLimit(UNSTACKABLE, 1, 64));
        }

        @Test
        void itemCapWins() {
            assertEquals(100, CapacityMath.carryLimit(STACK, 4, 100));
        }

        @Test
        void noOverflowForLargeInputs() {
            assertEquals(Integer.MAX_VALUE, CapacityMath.carryLimit(99, Integer.MAX_VALUE, Integer.MAX_VALUE));
        }

        @Test
        void zeroStacksCarryNothing() {
            assertEquals(0, CapacityMath.carryLimit(STACK, 0, 64));
        }

        @Test
        void rejectsInvalidArguments() {
            assertThrows(IllegalArgumentException.class, () -> CapacityMath.carryLimit(0, 1, 64));
            assertThrows(IllegalArgumentException.class, () -> CapacityMath.carryLimit(STACK, -1, 64));
            assertThrows(IllegalArgumentException.class, () -> CapacityMath.carryLimit(STACK, 1, -1));
        }
    }

    @Test
    void toIntClamped() {
        assertEquals(0, CapacityMath.toIntClamped(-5));
        assertEquals(0, CapacityMath.toIntClamped(0));
        assertEquals(123, CapacityMath.toIntClamped(123));
        assertEquals(Integer.MAX_VALUE, CapacityMath.toIntClamped(Long.MAX_VALUE));
    }
}
