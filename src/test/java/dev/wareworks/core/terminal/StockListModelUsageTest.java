package dev.wareworks.core.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.stock.StockRule;
import dev.wareworks.core.stock.StockRuleStatus;

/**
 * What the "most used" order does to the screen's list model (M24, issue #17): the search, the "only what is
 * available" filter and the paging keep working on top of it, exactly as they do on the other two orders.
 */
class StockListModelUsageTest {
    private static StockLine<String> line(String key, long total, long available, String name, String mod) {
        return new StockLine<>(key, total, available, name, mod);
    }

    private static StockListModel<String> filled() {
        StockListModel<String> model = new StockListModel<>();
        model.replaceAll(List.of(line("iron", 640, 640, "Iron Ingot", "minecraft"),
                line("copper", 320, 0, "Copper Ingot", "minecraft"),
                line("cog", 64, 64, "Cogwheel", "create"), line("brass", 128, 100, "Brass Ingot", "create")));
        return model;
    }

    private static List<String> keys(List<StockLine<String>> lines) {
        return lines.stream().map(StockLine::key).toList();
    }

    @Test
    void aFreshModelStartsAtTheDefaultOrderAndWithoutAHistory() {
        StockListModel<String> model = filled();
        assertSame(TerminalSort.DEFAULT, model.sort());
        assertEquals(0L, model.usage().countFor("iron"));
        assertEquals(List.of("iron", "brass", "cog", "copper"), keys(model.visible()));
    }

    @Test
    void theListIsReorderedWhenTheCountsArrive() {
        StockListModel<String> model = filled();
        model.setSort(TerminalSort.USED);
        assertEquals(List.of("iron", "brass", "cog", "copper"), keys(model.visible()),
                "no counts yet, so the amount order");

        TerminalUsage<String> usage = new TerminalUsage<>();
        usage.record("cog");
        usage.record("cog");
        usage.record("copper");
        model.setUsage(usage);
        assertEquals(List.of("cog", "copper", "iron", "brass"), keys(model.visible()));

        // The same store counting one more request is enough; the model re-sorts when it is told.
        usage.record("copper");
        usage.record("copper");
        model.setUsage(usage);
        assertEquals(List.of("copper", "cog", "iron", "brass"), keys(model.visible()));
    }

    @Test
    void nullCountsMeanNoHistory() {
        StockListModel<String> model = filled();
        TerminalUsage<String> usage = new TerminalUsage<>();
        usage.record("cog");
        model.setUsage(usage);
        model.setSort(TerminalSort.USED);
        assertEquals(List.of("cog", "iron", "brass", "copper"), keys(model.visible()));
        model.setUsage(null);
        assertEquals(0L, model.usage().countFor("cog"));
        assertEquals(List.of("iron", "brass", "cog", "copper"), keys(model.visible()));
    }

    @Test
    void theSearchKeepsWorkingOnTopOfTheOrder() {
        StockListModel<String> model = filled();
        TerminalUsage<String> usage = new TerminalUsage<>();
        usage.record("copper");
        usage.record("copper");
        usage.record("iron");
        model.setUsage(usage);
        model.setSort(TerminalSort.USED);

        model.setQuery("ingot");
        assertEquals(List.of("copper", "iron", "brass"), keys(model.visible()),
                "the search narrows, the order still leads with what the player uses");
        model.setQuery("@create");
        assertEquals(List.of("brass", "cog"), keys(model.visible()), "nothing the player uses is left, so amounts");
    }

    @Test
    void theFilterAndThePagingKeepWorkingOnTopOfTheOrder() {
        StockListModel<String> model = filled();
        TerminalUsage<String> usage = new TerminalUsage<>();
        usage.record("copper");
        model.setUsage(usage);
        model.setSort(TerminalSort.USED);
        assertEquals(List.of("copper", "iron", "brass", "cog"), keys(model.visible()));

        model.setInStockOnly(true);
        assertEquals(List.of("iron", "brass", "cog"), keys(model.visible()),
                "copper is the favourite but none of it is available");

        model.setInStockOnly(false);
        assertEquals(2, model.rowCount(2));
        assertEquals(List.of("copper", "iron"), keys(model.window(0, 2, 1)));
        assertEquals(List.of("brass", "cog"), keys(model.window(1, 2, 1)));
    }

    /** And so does the row a stock rule keeps at zero stock, which is not an offer at all (M15, M24 review fix). */
    @Test
    void aRuledRowAtZeroStockStaysBehindStockEvenWhenItIsTheFavourite() {
        StockListModel<String> model = new StockListModel<>();
        StockLine<String> andesite = new StockLine<>("andesite", 0, 0, false, 0,
                Optional.of(StockRuleStatus.BELOW_MINIMUM), 0, StockRule.UNSET, "Andesite Alloy", "create");
        model.replaceAll(List.of(andesite, line("zinc", 5, 5, "Zinc Ingot", "create")));
        TerminalUsage<String> usage = new TerminalUsage<>();
        for (int i = 0; i < 20; i++)
            usage.record("andesite");
        model.setUsage(usage);
        model.setSort(TerminalSort.USED);
        assertTrue(model.find("andesite").orElseThrow().isShown(), "the row a rule keeps is shown");
        assertFalse(model.find("andesite").orElseThrow().isProducibleOnly(), "and it is not an offer");
        assertEquals(List.of("zinc", "andesite"), keys(model.visible()));
    }

    @Test
    void anOfferAtZeroStockStaysBehindStockEvenWhenItIsTheFavourite() {
        StockListModel<String> model = new StockListModel<>();
        model.replaceAll(List.of(new StockLine<>("acacia", 0, 0, true, 64, "Acacia Planks", "minecraft"),
                line("zinc", 5, 5, "Zinc Ingot", "create")));
        TerminalUsage<String> usage = new TerminalUsage<>();
        for (int i = 0; i < 20; i++)
            usage.record("acacia");
        model.setUsage(usage);
        model.setSort(TerminalSort.USED);
        assertTrue(model.find("acacia").orElseThrow().isProducibleOnly());
        assertEquals(List.of("zinc", "acacia"), keys(model.visible()));
    }
}
