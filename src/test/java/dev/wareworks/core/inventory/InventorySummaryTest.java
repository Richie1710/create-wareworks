package dev.wareworks.core.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class InventorySummaryTest {
    private static final int STACK = 64;
    private static final int LIMIT = 99;
    private static final int TOP = 3;

    private static InventorySnapshot<String> fourKinds() {
        return InventorySnapshot.<String>builder(7)
                .add("iron", 64, LIMIT, STACK)
                .add("gold", 20, LIMIT, STACK)
                .addEmpty(LIMIT)
                .add("iron", 36, LIMIT, STACK)
                .add("diamond", 5, LIMIT, STACK)
                .add("dirt", 5, LIMIT, STACK)
                .addEmpty(LIMIT)
                .build();
    }

    @Test
    void summarisesSlotsAndTopEntries() {
        InventorySummary<String> summary = InventorySummary.of(fourKinds(), TOP);
        assertEquals(5, summary.usedSlots());
        assertEquals(7, summary.totalSlots());
        assertEquals(4, summary.distinctKeys());
        assertEquals(List.of(new KeyCount<>("iron", 100), new KeyCount<>("gold", 20), new KeyCount<>("diamond", 5)),
                summary.topEntries(), "largest first, ties in first-appearance order");
        assertEquals(1, summary.hiddenKeys());
        assertFalse(summary.isEmpty());
    }

    @Test
    void fewerKeysThanRequestedEntries() {
        InventorySnapshot<String> snapshot = InventorySnapshot.<String>builder(2)
                .add("iron", 3, LIMIT, STACK)
                .addEmpty(LIMIT)
                .build();
        InventorySummary<String> summary = InventorySummary.of(snapshot, TOP);
        assertEquals(List.of(new KeyCount<>("iron", 3)), summary.topEntries());
        assertEquals(0, summary.hiddenKeys());
    }

    @Test
    void zeroSlotsAndNegativeEntryCount() {
        assertSame(InventorySummary.empty(), InventorySummary.of(InventorySnapshot.<String>empty(), TOP));
        InventorySummary<String> none = InventorySummary.of(fourKinds(), -1);
        assertTrue(none.topEntries().isEmpty());
        assertEquals(4, none.hiddenKeys());
    }

    @Test
    void emptyInventoryWithSlots() {
        InventorySnapshot<String> snapshot = InventorySnapshot.<String>builder(2).addEmpty(LIMIT).addEmpty(LIMIT).build();
        InventorySummary<String> summary = InventorySummary.of(snapshot, TOP);
        assertTrue(summary.isEmpty());
        assertEquals(2, summary.totalSlots());
        assertNotEquals(InventorySummary.empty(), summary, "slot count distinguishes an empty chest from no inventory");
    }

    @Test
    void equalityDetectsVisibleChanges() {
        InventorySummary<String> first = InventorySummary.of(fourKinds(), TOP);
        assertEquals(first, InventorySummary.of(fourKinds(), TOP), "same contents, equal summaries");

        InventorySnapshot<String> moreDirt = InventorySnapshot.<String>builder(7)
                .add("iron", 64, LIMIT, STACK)
                .add("gold", 20, LIMIT, STACK)
                .addEmpty(LIMIT)
                .add("iron", 36, LIMIT, STACK)
                .add("diamond", 5, LIMIT, STACK)
                .add("dirt", 6, LIMIT, STACK)
                .addEmpty(LIMIT)
                .build();
        InventorySummary<String> second = InventorySummary.of(moreDirt, TOP);
        assertNotEquals(first, second, "top 3 unchanged but dirt now outranks diamond");
    }

    @Test
    void groupingMergesKeysOfTheSameGroup() {
        // Keys "type:variant" grouped by type, like item keys grouped by item.
        InventorySnapshot<String> snapshot = InventorySnapshot.<String>builder(7)
                .add("pickaxe:worn", 1, LIMIT, 1)
                .add("gold:plain", 20, LIMIT, STACK)
                .add("pickaxe:new", 1, LIMIT, 1)
                .addEmpty(LIMIT)
                .add("book:sharpness", 1, LIMIT, 1)
                .add("pickaxe:enchanted", 1, LIMIT, 1)
                .add("book:unbreaking", 1, LIMIT, 1)
                .build();
        InventorySummary<String> summary = InventorySummary.of(snapshot, TOP, key -> key.substring(0, key.indexOf(':')));
        assertEquals(6, summary.usedSlots());
        assertEquals(7, summary.totalSlots());
        assertEquals(3, summary.distinctKeys(), "distinct groups, not distinct keys");
        assertEquals(List.of(new KeyCount<>("gold", 20), new KeyCount<>("pickaxe", 3), new KeyCount<>("book", 2)),
                summary.topEntries());
        assertEquals(0, summary.hiddenKeys());

        InventorySummary<String> top1 = InventorySummary.of(snapshot, 1, key -> key.substring(0, key.indexOf(':')));
        assertEquals(List.of(new KeyCount<>("gold", 20)), top1.topEntries());
        assertEquals(2, top1.hiddenKeys());
    }

    @Test
    void groupingTiesKeepFirstAppearanceOfTheGroup() {
        InventorySnapshot<String> snapshot = InventorySnapshot.<String>builder(3)
                .add("b:1", 2, LIMIT, STACK)
                .add("a:1", 1, LIMIT, STACK)
                .add("a:2", 1, LIMIT, STACK)
                .build();
        InventorySummary<String> summary = InventorySummary.of(snapshot, TOP, key -> key.substring(0, 1));
        assertEquals(List.of(new KeyCount<>("b", 2), new KeyCount<>("a", 2)), summary.topEntries());
    }

    @Test
    void identityGroupingEqualsPlainSummary() {
        assertEquals(InventorySummary.of(fourKinds(), TOP), InventorySummary.of(fourKinds(), TOP, key -> key));
        assertSame(InventorySummary.empty(), InventorySummary.of(InventorySnapshot.<String>empty(), TOP, key -> key));
    }

    @Test
    void groupingRejectsNull() {
        assertThrows(NullPointerException.class, () -> InventorySummary.of(fourKinds(), TOP, key -> null));
        assertThrows(NullPointerException.class,
                () -> InventorySummary.<String, String>of(fourKinds(), TOP, null));
    }

    @Test
    void topEntriesAreCopied() {
        List<KeyCount<String>> entries = new ArrayList<>(List.of(new KeyCount<>("iron", 1)));
        InventorySummary<String> summary = new InventorySummary<>(1, 1, 1, entries);
        entries.clear();
        assertEquals(1, summary.topEntries().size());
        assertThrows(UnsupportedOperationException.class, () -> summary.topEntries().clear());
    }

    @Test
    void constructorRejectsInconsistentValues() {
        List<KeyCount<String>> one = List.of(new KeyCount<>("iron", 1));
        assertThrows(IllegalArgumentException.class, () -> new InventorySummary<>(0, -1, 0, List.<KeyCount<String>>of()));
        assertThrows(IllegalArgumentException.class, () -> new InventorySummary<>(2, 1, 1, one));
        assertThrows(IllegalArgumentException.class, () -> new InventorySummary<>(-1, 1, 1, one));
        assertThrows(IllegalArgumentException.class, () -> new InventorySummary<>(1, 1, 0, one));
        assertThrows(NullPointerException.class, () -> new InventorySummary<String>(0, 0, 0, null));
    }

    @Test
    void sanitizedClampsUntrustedValues() {
        List<KeyCount<String>> entries = Arrays.asList(new KeyCount<>("iron", 4), null);
        InventorySummary<String> summary = InventorySummary.sanitized(40, 27, 0, entries);
        assertEquals(27, summary.usedSlots());
        assertEquals(27, summary.totalSlots());
        assertEquals(1, summary.distinctKeys());
        assertEquals(List.of(new KeyCount<>("iron", 4)), summary.topEntries());

        assertSame(InventorySummary.empty(), InventorySummary.sanitized(-3, -5, -1, null));
        InventorySummary<String> negativeUsed = InventorySummary.sanitized(-3, 9, 2, List.of());
        assertEquals(0, negativeUsed.usedSlots());
        assertEquals(2, negativeUsed.hiddenKeys());

        // A negative distinct count with slots present must be clamped, not rejected by the constructor.
        InventorySummary<String> negativeDistinct = InventorySummary.sanitized(1, 27, -4, List.of());
        assertEquals(0, negativeDistinct.distinctKeys());
        assertEquals(27, negativeDistinct.totalSlots());
        InventorySummary<String> negativeDistinctWithEntry =
                InventorySummary.sanitized(1, 27, -4, List.of(new KeyCount<>("iron", 3)));
        assertEquals(1, negativeDistinctWithEntry.distinctKeys());
    }
}
