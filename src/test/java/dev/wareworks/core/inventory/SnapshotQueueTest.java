package dev.wareworks.core.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class SnapshotQueueTest {
    private static List<String> drain(SnapshotQueue<String> queue) {
        List<String> polled = new ArrayList<>();
        for (Optional<String> next = queue.poll(); next.isPresent(); next = queue.poll())
            polled.add(next.get());
        return polled;
    }

    @Test
    void urgentBeforeBackgroundFirstInFirstOut() {
        SnapshotQueue<String> queue = new SnapshotQueue<>();
        assertTrue(queue.addBackground("b1"));
        assertTrue(queue.addUrgent("u1"));
        assertTrue(queue.addBackground("b2"));
        assertTrue(queue.addUrgent("u2"));
        assertEquals(4, queue.size());
        assertEquals(2, queue.urgentCount());
        assertEquals(List.of("u1", "u2", "b1", "b2"), drain(queue));
        assertTrue(queue.isEmpty());
        assertEquals(Optional.empty(), queue.poll());
    }

    @Test
    void duplicatesKeepTheirPlace() {
        SnapshotQueue<String> queue = new SnapshotQueue<>();
        queue.addUrgent("a");
        queue.addUrgent("b");
        assertFalse(queue.addUrgent("a"), "already queued");
        assertFalse(queue.addBackground("b"), "an urgent entry is not demoted");
        assertEquals(2, queue.size());
        assertEquals(List.of("a", "b"), drain(queue));

        queue.addUrgent("a");
        queue.poll();
        assertTrue(queue.addUrgent("a"), "a polled location can be queued again");
    }

    @Test
    void backgroundEntriesArePromoted() {
        SnapshotQueue<String> queue = new SnapshotQueue<>();
        queue.addBackground("x");
        queue.addBackground("y");
        queue.addUrgent("z");
        assertTrue(queue.addUrgent("y"), "promoted");
        assertTrue(queue.isUrgent("y"));
        assertFalse(queue.isUrgent("x"));
        assertEquals(3, queue.size(), "no duplicate after the promotion");
        assertEquals(List.of("z", "y", "x"), drain(queue));
    }

    @Test
    void removeAndClear() {
        SnapshotQueue<String> queue = new SnapshotQueue<>();
        queue.addUrgent("a");
        queue.addBackground("b");
        assertTrue(queue.contains("a"));
        assertTrue(queue.remove("a"));
        assertFalse(queue.remove("a"), "not queued any more");
        assertTrue(queue.remove("b"));
        assertTrue(queue.isEmpty());
        queue.addUrgent("c");
        queue.addBackground("d");
        queue.clear();
        assertEquals(0, queue.size());
        assertFalse(queue.contains("c"));
        assertThrows(NullPointerException.class, () -> queue.addUrgent(null));
        assertThrows(NullPointerException.class, () -> queue.addBackground(null));
        assertThrows(NullPointerException.class, () -> queue.remove(null));
    }
}
