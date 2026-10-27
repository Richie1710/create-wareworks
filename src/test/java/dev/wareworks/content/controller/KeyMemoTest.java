package dev.wareworks.content.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * {@link KeyMemo} (M31, issue #21, ADR-073): the empty-container memo the terminal's fluid cells ask about every item
 * type in stock on every push. A warehouse with more item types than the smallest bound must still answer a second
 * pass entirely from memory.
 */
class KeyMemoTest {
    private static final int MINIMUM = 256;

    private static List<String> keys(int count) {
        List<String> keys = new ArrayList<>(count);
        for (int index = 0; index < count; index++)
            keys.add("item_" + index);
        return keys;
    }

    /** One pass over the index, as {@code emptyContainersInStock} makes it: sized first, then every key asked. */
    private static int pass(KeyMemo<String> memo, List<String> keys) {
        memo.expect(keys.size());
        int empties = 0;
        for (String key : keys) {
            if (memo.test(key))
                empties++;
        }
        return empties;
    }

    @Test
    void aSecondPassOverTwoThousandKeysProbesNothing() {
        AtomicInteger probes = new AtomicInteger();
        KeyMemo<String> memo = new KeyMemo<>(key -> {
            probes.incrementAndGet();
            return key.endsWith("7");
        }, MINIMUM);
        List<String> keys = keys(2000);

        assertEquals(200, pass(memo, keys), "every tenth key is an empty container");
        assertEquals(2000, probes.get(), "the first pass probes each key once");
        assertEquals(200, pass(memo, keys), "the same answers");
        assertEquals(2000, probes.get(), "the second pass is answered from memory");
        assertEquals(2000, memo.size(), "and nothing was cleared");
    }

    @Test
    void withoutSizingTheSmallestBoundIsKeptAndClearedWhenFull() {
        AtomicInteger probes = new AtomicInteger();
        KeyMemo<String> memo = new KeyMemo<>(key -> {
            probes.incrementAndGet();
            return false;
        }, 4);
        for (String key : keys(5))
            assertFalse(memo.test(key));
        assertEquals(5, probes.get());
        assertEquals(1, memo.size(), "the fifth answer cleared the four before it");
    }

    @Test
    void aGrowingIndexClearsAtMostOncePerGrowth() {
        AtomicInteger probes = new AtomicInteger();
        KeyMemo<String> memo = new KeyMemo<>(key -> {
            probes.incrementAndGet();
            return true;
        }, MINIMUM);
        pass(memo, keys(300));
        assertEquals(300, probes.get());
        List<String> grown = keys(700);
        pass(memo, grown);
        int afterGrowth = probes.get();
        assertTrue(afterGrowth <= 300 + 700, "at most one probe per key while growing");
        pass(memo, grown);
        assertEquals(afterGrowth, probes.get(), "the grown index is then answered from memory");
    }

    @Test
    void clearForgetsAndRejectsABadBound() {
        AtomicInteger probes = new AtomicInteger();
        KeyMemo<String> memo = new KeyMemo<>(key -> {
            probes.incrementAndGet();
            return true;
        }, MINIMUM);
        assertTrue(memo.test("bucket"));
        memo.clear();
        assertEquals(0, memo.size());
        assertTrue(memo.test("bucket"));
        assertEquals(2, probes.get(), "a cleared memo asks again");
        assertThrows(IllegalArgumentException.class, () -> new KeyMemo<String>(key -> true, 0));
    }
}
