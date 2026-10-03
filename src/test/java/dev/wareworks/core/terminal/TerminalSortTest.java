package dev.wareworks.core.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.stock.StockRule;
import dev.wareworks.core.stock.StockRuleStatus;

/**
 * The three orders of the terminal's item list (M24, issue #17).
 * <p>
 * The {@link TerminalSort#AMOUNT} and {@link TerminalSort#NAME} cases are a <b>regression</b> test: they pin the exact
 * order those two answered before "most used" existed, including their tie-breaks, so adding a third order cannot
 * quietly change the two a player already knows.
 */
class TerminalSortTest {
    private static StockLine<String> line(String key, long total, long available, String name, String mod) {
        return new StockLine<>(key, total, available, name, mod);
    }

    /** An item the warehouse holds none of but a production station can make (M11, ADR-024). */
    private static StockLine<String> producible(String key, String name) {
        return new StockLine<>(key, 0, 0, true, 64, name, "minecraft");
    }

    /**
     * The third kind of row with nothing in it: an item a <b>stock rule</b> governs that the warehouse has run out of
     * (M15). Neither stocked nor producible, so {@code isProducibleOnly()} is false for it.
     */
    private static StockLine<String> ruledEmpty(String key, String name) {
        return new StockLine<>(key, 0, 0, false, 0, Optional.of(StockRuleStatus.BELOW_MINIMUM), 0, StockRule.UNSET,
                name, "minecraft");
    }

    private static final List<StockLine<String>> STOCK = List.of(line("iron", 640, 640, "Iron Ingot", "minecraft"),
            line("copper", 320, 0, "Copper Ingot", "minecraft"), line("cog", 64, 64, "Cogwheel", "create"),
            line("brass", 128, 100, "Brass Ingot", "create"));

    private static List<String> sorted(TerminalSort sort, TerminalUsageCounts<String> usage,
            List<StockLine<String>> lines) {
        List<StockLine<String>> copy = new ArrayList<>(lines);
        copy.sort(sort.comparator(usage));
        return copy.stream().map(StockLine::key).toList();
    }

    private static List<String> sorted(TerminalSort sort, List<StockLine<String>> lines) {
        List<StockLine<String>> copy = new ArrayList<>(lines);
        copy.sort(sort.comparator());
        return copy.stream().map(StockLine::key).toList();
    }

    private static TerminalUsageCounts<String> history(String... keysByFrequency) {
        TerminalUsage<String> usage = new TerminalUsage<>();
        // The first key given is requested most often, the last one once.
        for (int i = 0; i < keysByFrequency.length; i++) {
            for (int n = keysByFrequency.length - i; n > 0; n--)
                usage.record(keysByFrequency[i]);
        }
        return usage;
    }

    // --- what the two existing orders answered before "most used" existed ------------------------------------------

    @Test
    void amountOrdersByAvailableThenStockThenNameThenModId() {
        assertEquals(List.of("iron", "brass", "cog", "copper"), sorted(TerminalSort.AMOUNT, STOCK));
        // Same available: the larger stock first, then the name, then the mod id.
        List<StockLine<String>> ties = List.of(line("small", 10, 5, "Same Name", "aaa"),
                line("large", 20, 5, "Same Name", "zzz"), line("other", 10, 5, "Same Name", "bbb"),
                line("named", 10, 5, "Another Name", "aaa"));
        assertEquals(List.of("large", "named", "small", "other"), sorted(TerminalSort.AMOUNT, ties));
    }

    @Test
    void nameOrdersCaseInsensitivelyThenByModIdThenByAvailable() {
        assertEquals(List.of("brass", "cog", "copper", "iron"), sorted(TerminalSort.NAME, STOCK));
        List<StockLine<String>> ties = List.of(line("zinc", 1, 1, "zinc ingot", "create"),
                line("ZINC", 1, 1, "Zinc Ingot", "another"), line("much", 9, 9, "Zinc Ingot", "create"));
        assertEquals(List.of("ZINC", "much", "zinc"), sorted(TerminalSort.NAME, ties));
    }

    @Test
    void producibleOnlyLinesAreLastInEveryOrder() {
        List<StockLine<String>> lines = List.of(producible("acacia", "Acacia Planks"),
                line("iron", 10, 0, "Iron Ingot", "minecraft"), line("zinc", 5, 5, "Zinc Ingot", "create"));
        assertEquals(List.of("zinc", "iron", "acacia"), sorted(TerminalSort.AMOUNT, lines));
        assertEquals(List.of("iron", "zinc", "acacia"), sorted(TerminalSort.NAME, lines));
        // Even a favourite the warehouse has just run out of stays behind what a player can have right now.
        assertEquals(List.of("zinc", "iron", "acacia"),
                sorted(TerminalSort.USED, history("acacia"), lines));
    }

    // --- the third order ------------------------------------------------------------------------------------------

    @Test
    void usedPutsWhatThisPlayerAsksForMostFirst() {
        assertEquals(List.of("cog", "copper", "iron", "brass"),
                sorted(TerminalSort.USED, history("cog", "copper"), STOCK),
                "the two the player uses lead, in their own order; the rest keeps the amount order");
    }

    @Test
    void usedWithoutAHistoryIsExactlyTheAmountOrder() {
        assertEquals(sorted(TerminalSort.AMOUNT, STOCK), sorted(TerminalSort.USED, STOCK));
        assertEquals(sorted(TerminalSort.AMOUNT, STOCK),
                sorted(TerminalSort.USED, TerminalUsageCounts.none(), STOCK));
        assertEquals(sorted(TerminalSort.AMOUNT, STOCK), sorted(TerminalSort.USED, new TerminalUsage<>(), STOCK),
                "a player who has never requested anything sees the amount order, not an empty or random list");
        assertEquals(sorted(TerminalSort.AMOUNT, STOCK), sorted(TerminalSort.USED, null, STOCK),
                "and so does a screen whose counts have not arrived yet");
    }

    @Test
    void anItemThePlayerNeverAskedForKeepsTheAmountOrderBehindTheOnesTheyDid() {
        TerminalUsage<String> usage = new TerminalUsage<>();
        usage.record("copper");
        assertEquals(List.of("copper", "iron", "brass", "cog"), sorted(TerminalSort.USED, usage, STOCK),
                "one request is enough to lift an item the warehouse has none of to the front");
    }

    @Test
    void equalCountsFallBackToTheAmountOrderAndNotToSomethingInvisible() {
        TerminalUsage<String> usage = new TerminalUsage<>();
        usage.record("cog");
        usage.record("brass");
        usage.record("cog");
        usage.record("brass");
        assertEquals(2L, usage.countFor("cog"));
        assertEquals(2L, usage.countFor("brass"));
        // "brass" was requested more recently, but the player can see 100 available against 64: amounts decide.
        assertEquals(List.of("brass", "cog", "iron", "copper"), sorted(TerminalSort.USED, usage, STOCK));
    }

    /**
     * The row a stock rule keeps at zero stock (M15) is not an offer, so the "offers last" key does not catch it — and
     * the usage key would put a favourite the warehouse has just run out of at the very top of the list, where every
     * click on it is a guaranteed refusal (M24 review fix).
     */
    @Test
    void aRowAStockRuleKeepsAtZeroStockNeverLeadsTheUsedOrder() {
        StockLine<String> andesite = ruledEmpty("andesite", "Andesite Alloy");
        List<StockLine<String>> lines = List.of(andesite, line("zinc", 5, 5, "Zinc Ingot", "create"),
                line("copper", 320, 0, "Copper Ingot", "minecraft"), producible("acacia", "Acacia Planks"));
        assertEquals(0L, andesite.orderable(), "a row a rule keeps can be clicked, but never granted");
        assertEquals(List.of("zinc", "copper", "andesite", "acacia"),
                sorted(TerminalSort.USED, history("andesite"), lines),
                "the favourite the warehouse ran out of sits behind everything it still holds");
    }

    @Test
    void whatIsStoredLeadsEvenWhenAllOfItIsPromised() {
        TerminalUsage<String> usage = new TerminalUsage<>();
        usage.record("copper");
        // 320 stored and 0 available is stock, and the player keeps asking for it: the head key reads total, not
        // available, or a favourite would drop behind an empty row whenever a request promised the last of it.
        assertEquals(List.of("copper", "zinc", "andesite"),
                sorted(TerminalSort.USED, usage, List.of(ruledEmpty("andesite", "Andesite Alloy"),
                        line("zinc", 5, 5, "Zinc Ingot", "create"),
                        line("copper", 320, 0, "Copper Ingot", "minecraft"))));
    }

    @Test
    void usedWithoutAHistoryIsTheAmountOrderEvenWithEmptyRows() {
        // The head of "most used" groups three ways (stocked, empty, an offer), which is the grouping the amount
        // order's own keys produce, so the fall-back is still exactly that order and not merely close to it.
        List<StockLine<String>> lines = List.of(producible("acacia", "Acacia Planks"),
                ruledEmpty("andesite", "Andesite Alloy"), line("zinc", 5, 5, "Zinc Ingot", "create"),
                line("copper", 320, 0, "Copper Ingot", "minecraft"));
        assertEquals(List.of("zinc", "copper", "andesite", "acacia"), sorted(TerminalSort.AMOUNT, lines));
        assertEquals(sorted(TerminalSort.AMOUNT, lines), sorted(TerminalSort.USED, lines));
    }

    @Test
    void theOrderIsTotalSoTheSameWarehouseAlwaysLooksTheSame() {
        List<StockLine<String>> lines = List.of(line("a", 5, 5, "Same Name", "zzz"),
                line("b", 5, 5, "Same Name", "aaa"), line("c", 5, 5, "Other Name", "aaa"));
        TerminalUsageCounts<String> usage = history("a", "b", "c");
        List<String> once = sorted(TerminalSort.USED, usage, lines);
        assertEquals(List.of("a", "b", "c"), once);
        assertEquals(once, sorted(TerminalSort.USED, usage, lines.reversed()), "the input order cannot change it");
    }

    // --- the button and the saved choice ---------------------------------------------------------------------------

    @Test
    void theButtonCyclesThroughAllThreeOrders() {
        assertEquals(TerminalSort.USED, TerminalSort.AMOUNT.next());
        assertEquals(TerminalSort.NAME, TerminalSort.USED.next());
        assertEquals(TerminalSort.AMOUNT, TerminalSort.NAME.next(), "the button cycles back");
        assertEquals(3, TerminalSort.values().length);
    }

    @Test
    void everyOrderHasItsOwnLangKey() {
        assertEquals("gui.terminal.sort.amount", TerminalSort.AMOUNT.langKey());
        assertEquals("gui.terminal.sort.used", TerminalSort.USED.langKey());
        assertEquals("gui.terminal.sort.name", TerminalSort.NAME.langKey());
    }

    @Test
    void aSavedChoiceIsReadByNameAndFallsBackToTheDefault() {
        assertSame(TerminalSort.AMOUNT, TerminalSort.DEFAULT, "what a player who never chose sees");
        for (TerminalSort sort : TerminalSort.values())
            assertSame(sort, TerminalSort.byName(sort.name()), "a saved choice comes back");
        assertSame(TerminalSort.USED, TerminalSort.byName("used"));
        assertSame(TerminalSort.DEFAULT, TerminalSort.byName("recently_requested"));
        assertSame(TerminalSort.DEFAULT, TerminalSort.byName(""));
        assertSame(TerminalSort.DEFAULT, TerminalSort.byName(null));
    }
}
