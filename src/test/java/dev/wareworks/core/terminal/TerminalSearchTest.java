package dev.wareworks.core.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The query rules of the warehouse terminal's search box. */
class TerminalSearchTest {
    private static final StockLine<String> IRON =
            new StockLine<>("iron", 64, 64, "Iron Ingot", "minecraft");
    private static final StockLine<String> COG =
            new StockLine<>("cog", 32, 16, "Cogwheel", "create");

    @Test
    void blankQueryMatchesEverything() {
        assertTrue(TerminalSearch.matches(IRON, ""));
        assertTrue(TerminalSearch.matches(IRON, "   "));
        assertTrue(TerminalSearch.matches(IRON, null));
    }

    @Test
    void nameMatchesCaseInsensitivelyAnywhere() {
        assertTrue(TerminalSearch.matches(IRON, "iron"));
        assertTrue(TerminalSearch.matches(IRON, "INGOT"));
        assertTrue(TerminalSearch.matches(IRON, "n ing"), "every token matches somewhere in the name");
        assertFalse(TerminalSearch.matches(IRON, "golden"));
    }

    @Test
    void modPrefixMatchesTheModId() {
        assertTrue(TerminalSearch.matches(COG, "@create"));
        assertTrue(TerminalSearch.matches(COG, "@cre"));
        assertFalse(TerminalSearch.matches(COG, "@minecraft"));
        assertTrue(TerminalSearch.matches(COG, "@"), "a lone prefix does not empty the list while typing");
        assertFalse(TerminalSearch.matches(COG, "@create iron"), "name and mod tokens combine with AND");
    }

    @Test
    void filterKeepsTheInputOrder() {
        List<StockLine<String>> lines = List.of(IRON, COG);
        assertEquals(lines, TerminalSearch.filter(lines, ""));
        assertEquals(List.of(COG), TerminalSearch.filter(lines, "@create"));
        assertTrue(TerminalSearch.filter(lines, "nothing").isEmpty());
    }

    @Test
    void linesWithoutTextsNeverThrow() {
        StockLine<String> bare = new StockLine<>("x", 1, 1, null, null);
        assertTrue(TerminalSearch.matches(bare, ""));
        assertFalse(TerminalSearch.matches(bare, "anything"));
        assertEquals("", bare.name());
        assertEquals("", bare.modId());
    }
}
