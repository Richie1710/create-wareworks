package dev.wareworks.core.computer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalInt;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/** The pages a computer reads the terminal's stock list in (issue #9, ADR-074). */
class ResultPageTest {
    private static final List<Integer> TEN = IntStream.rangeClosed(1, 10).boxed().toList();

    @Test
    void firstPageAndTheNextStart() {
        ResultPage page = ResultPage.of(1, 4, TEN.size());
        assertEquals(List.of(1, 2, 3, 4), page.slice(TEN));
        assertEquals(OptionalInt.of(5), page.nextStart());
        ResultPage next = ResultPage.of(page.nextStart().getAsInt(), 4, TEN.size());
        assertEquals(List.of(5, 6, 7, 8), next.slice(TEN));
        assertEquals(OptionalInt.of(9), next.nextStart());
    }

    @Test
    void theLastPageHasNoNextStart() {
        ResultPage last = ResultPage.of(9, 4, TEN.size());
        assertEquals(List.of(9, 10), last.slice(TEN));
        assertTrue(last.nextStart().isEmpty());
        ResultPage exact = ResultPage.of(7, 4, TEN.size());
        assertEquals(List.of(7, 8, 9, 10), exact.slice(TEN));
        assertTrue(exact.nextStart().isEmpty(), "a page that ends exactly at the end has no next one");
    }

    @Test
    void emptyListsAndStartsPastTheEndAreEmptyPages() {
        ResultPage empty = ResultPage.of(1, ResultPage.MAX_LIMIT, 0);
        assertEquals(List.of(), empty.slice(List.of()));
        assertTrue(empty.nextStart().isEmpty());
        ResultPage past = ResultPage.of(50, 10, TEN.size());
        assertEquals(List.of(), past.slice(TEN));
        assertTrue(past.nextStart().isEmpty());
        ResultPage huge = ResultPage.of(Integer.MAX_VALUE, ResultPage.MAX_LIMIT, TEN.size());
        assertEquals(List.of(), huge.slice(TEN), "no overflow at the largest start");
    }

    @Test
    void boundsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> ResultPage.of(0, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> ResultPage.of(-3, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> ResultPage.of(1, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> ResultPage.of(1, ResultPage.MAX_LIMIT + 1, 10));
        List<Integer> thousand = IntStream.range(0, 1000).boxed().toList();
        assertEquals(ResultPage.MAX_LIMIT, ResultPage.of(1, ResultPage.MAX_LIMIT, 1000).slice(thousand).size());
    }

    @Test
    void theMessagesAreTheOnesAComputerIsShown() {
        assertEquals("start must be at least 1, got 0",
                assertThrows(IllegalArgumentException.class, () -> ResultPage.of(0, 1, 1)).getMessage());
        assertEquals("limit must be between 1 and 256, got 300",
                assertThrows(IllegalArgumentException.class, () -> ResultPage.of(1, 300, 1)).getMessage());
    }
}
