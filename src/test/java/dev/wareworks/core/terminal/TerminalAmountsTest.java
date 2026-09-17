package dev.wareworks.core.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.terminal.TerminalAmounts.Click;

/** How the terminal screen turns a click and its modifiers into a requested amount. */
class TerminalAmountsTest {
    private static final int MAX = 1024;
    private static final int STACK = 64;

    @Test
    void clampKeepsAmountsInRange() {
        assertEquals(1, TerminalAmounts.clamp(0, MAX));
        assertEquals(1, TerminalAmounts.clamp(-17, MAX));
        assertEquals(MAX, TerminalAmounts.clamp(MAX + 1, MAX));
        assertEquals(1, TerminalAmounts.clamp(5, 0), "a max below one is treated as one");
        assertEquals(7, TerminalAmounts.clamp(7, MAX));
    }

    @Test
    void steppingSaturatesInsteadOfOverflowing() {
        assertEquals(10, TerminalAmounts.stepped(9, 1, 1, MAX));
        assertEquals(65, TerminalAmounts.stepped(1, 1, 64, MAX));
        assertEquals(1, TerminalAmounts.stepped(10, -1, 64, MAX));
        assertEquals(MAX, TerminalAmounts.stepped(Integer.MAX_VALUE, 1, 64, MAX));
        assertEquals(1, TerminalAmounts.stepped(Integer.MIN_VALUE, -1, 64, MAX));
    }

    @Test
    void plainClickAsksForTheSelectedAmount() {
        assertEquals(10, TerminalAmounts.amountFor(Click.SELECTED, 10, STACK, 640, MAX));
        assertEquals(MAX, TerminalAmounts.amountFor(Click.SELECTED, MAX + 500, STACK, 4096, MAX));
    }

    @Test
    void shiftClickAsksForOneStackAndControlClickForEverything() {
        assertEquals(STACK, TerminalAmounts.amountFor(Click.STACK, 1, STACK, 640, MAX));
        assertEquals(1, TerminalAmounts.amountFor(Click.STACK, 1, 1, 640, MAX), "unstackable items ask for one");
        assertEquals(640, TerminalAmounts.amountFor(Click.ALL, 1, STACK, 640, MAX));
        assertEquals(MAX, TerminalAmounts.amountFor(Click.ALL, 1, STACK, 100_000, MAX), "never above the config cap");
    }

    @Test
    void neverAsksForMoreThanIsAvailable() {
        assertEquals(5, TerminalAmounts.amountFor(Click.SELECTED, 64, STACK, 5, MAX));
        assertEquals(5, TerminalAmounts.amountFor(Click.STACK, 64, STACK, 5, MAX));
        assertEquals(5, TerminalAmounts.amountFor(Click.ALL, 64, STACK, 5, MAX));
    }

    @Test
    void withoutStockTheRawAmountIsKeptSoTheServerCanAnswer() {
        assertEquals(10, TerminalAmounts.amountFor(Click.SELECTED, 10, STACK, 0, MAX));
        assertEquals(STACK, TerminalAmounts.amountFor(Click.STACK, 10, STACK, 0, MAX));
        assertEquals(1, TerminalAmounts.amountFor(Click.ALL, 10, STACK, 0, MAX));
    }

    // --- production (M11, ADR-024): the bound is what is available plus what the server says can be made ------------

    @Test
    void everythingPossibleIncludesWhatCanBeMade() {
        assertEquals(76, TerminalAmounts.amountFor(Click.ALL, 1, STACK, 64, 12, MAX));
        assertEquals(20, TerminalAmounts.amountFor(Click.ALL, 1, STACK, 0, 20, MAX),
                "nothing in stock: everything possible is what a pattern can make");
        assertEquals(MAX, TerminalAmounts.amountFor(Click.ALL, 1, STACK, 0, 100_000, MAX), "never above the config cap");
    }

    @Test
    void aProducibleItemMayBeAskedForAboveWhatIsAvailable() {
        assertEquals(10, TerminalAmounts.amountFor(Click.SELECTED, 10, STACK, 0, 64, MAX));
        assertEquals(STACK, TerminalAmounts.amountFor(Click.STACK, 1, STACK, 0, 64, MAX));
        assertEquals(5, TerminalAmounts.amountFor(Click.SELECTED, 64, STACK, 2, 3, MAX),
                "clamped to what is there plus what can be made, not to one of them");
    }

    @Test
    void nothingAvailableAndNothingProducibleStillKeepsTheRawAmount() {
        assertEquals(10, TerminalAmounts.amountFor(Click.SELECTED, 10, STACK, 0, 0, MAX));
        assertEquals(10, TerminalAmounts.amountFor(Click.SELECTED, 10, STACK, 0, MAX),
                "the short form is the same call with nothing producible");
    }

    @Test
    void negativeNumbersFromASkewedPayloadCountAsNothing() {
        assertEquals(1, TerminalAmounts.amountFor(Click.ALL, 1, STACK, -5, -5, MAX));
        assertEquals(7, TerminalAmounts.amountFor(Click.ALL, 1, STACK, -5, 7, MAX));
    }
}
