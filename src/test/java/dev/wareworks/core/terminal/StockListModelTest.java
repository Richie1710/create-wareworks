package dev.wareworks.core.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** The list, search, sort and paging behaviour behind the warehouse terminal screen. */
class StockListModelTest {
    private static final int COLUMNS = 4;
    private static final int ROWS = 2;

    private static StockLine<String> line(String key, long total, long available, String name, String mod) {
        return new StockLine<>(key, total, available, name, mod);
    }

    private static StockListModel<String> filled() {
        StockListModel<String> model = new StockListModel<>();
        model.replaceAll(List.of(line("iron", 640, 640, "Iron Ingot", "minecraft"),
                line("copper", 320, 0, "Copper Ingot", "minecraft"),
                line("cog", 64, 64, "Cogwheel", "create"),
                line("brass", 128, 100, "Brass Ingot", "create")));
        return model;
    }

    private static List<String> keys(List<StockLine<String>> lines) {
        return lines.stream().map(StockLine::key).toList();
    }

    /** A line of an item the aisle holds none of but can make ({@code docs/warehouse-system.md} §3.4.2, ADR-024). */
    private static StockLine<String> producible(String key, long total, long available, long producibleAmount,
            String name) {
        return new StockLine<>(key, total, available, true, producibleAmount, name, "minecraft");
    }

    @Test
    void anItemAtZeroStockIsKeptWhileAPatternCanMakeIt() {
        StockListModel<String> model = new StockListModel<>();
        model.replaceAll(List.of(line("iron", 10, 10, "Iron Ingot", "minecraft"),
                producible("plank", 0, 0, 64, "Oak Planks"), line("gone", 0, 0, "Gone", "minecraft")));
        assertEquals(2, model.size(), "an empty item stays only while something can produce it");
        assertTrue(model.find("plank").isPresent());
        assertTrue(model.find("plank").orElseThrow().isProducibleOnly());
        assertEquals(64L, model.find("plank").orElseThrow().producibleAmount());
        assertEquals(Optional.empty(), model.find("gone"));
    }

    @Test
    void anOfferThatStopsBeingProducibleIsDropped() {
        StockListModel<String> model = new StockListModel<>();
        model.replaceAll(List.of(producible("plank", 0, 0, 64, "Oak Planks")));
        // The pattern was cleared: the item is neither in stock nor producible, so its row goes away.
        model.apply(List.of(line("plank", 0, 0, "Oak Planks", "minecraft")));
        assertEquals(Optional.empty(), model.find("plank"));
    }

    @Test
    void producibleItemsSortBehindEverythingInStockInBothOrders() {
        StockListModel<String> model = new StockListModel<>();
        model.replaceAll(List.of(producible("acacia", 0, 0, 64, "Acacia Planks"),
                line("iron", 10, 0, "Iron Ingot", "minecraft"), line("zinc", 5, 5, "Zinc Ingot", "create")));

        model.setSort(TerminalSort.AMOUNT);
        assertEquals(List.of("zinc", "iron", "acacia"), keys(model.visible()));

        model.setSort(TerminalSort.NAME);
        // "Acacia Planks" would lead a list sorted by name; it is an offer rather than stock, so it stays last.
        assertEquals(List.of("iron", "zinc", "acacia"), keys(model.visible()));
    }

    @Test
    void anItemInStockThatCanAlsoBeMadeIsOrderedLikeStock() {
        StockListModel<String> model = new StockListModel<>();
        model.replaceAll(List.of(producible("plank", 8, 8, 64, "Oak Planks"),
                line("zinc", 5, 5, "Zinc Ingot", "create")));
        model.setSort(TerminalSort.AMOUNT);
        assertEquals(List.of("plank", "zinc"), keys(model.visible()), "8 in stock outranks 5, producible or not");
        assertFalse(model.find("plank").orElseThrow().isProducibleOnly());
    }

    @Test
    void onlyAvailableHidesAnOfferThatCannotBeHandedOverNow() {
        StockListModel<String> model = new StockListModel<>();
        model.replaceAll(List.of(producible("acacia", 0, 0, 64, "Acacia Planks"),
                line("zinc", 5, 5, "Zinc Ingot", "create")));
        model.setInStockOnly(true);
        assertEquals(List.of("zinc"), keys(model.visible()), "nothing of a producible item can be handed over now");
    }

    @Test
    void replaceAllDropsEmptyEntriesAndKeepsTheRest() {
        StockListModel<String> model = new StockListModel<>();
        model.replaceAll(List.of(line("iron", 10, 10, "Iron Ingot", "minecraft"),
                line("gone", 0, 0, "Gone", "minecraft")));
        assertEquals(1, model.size());
        assertEquals(Optional.empty(), model.find("gone"));
        assertEquals(10L, model.find("iron").orElseThrow().total());
    }

    @Test
    void applyAddsChangesAndRemoves() {
        StockListModel<String> model = filled();
        model.apply(List.of(line("iron", 600, 12, "Iron Ingot", "minecraft"),
                line("gold", 7, 7, "Gold Ingot", "minecraft"), line("cog", 0, 0, "Cogwheel", "create")));
        assertEquals(4, model.size());
        assertEquals(12L, model.find("iron").orElseThrow().available());
        assertTrue(model.find("gold").isPresent());
        assertEquals(Optional.empty(), model.find("cog"), "an entry with total 0 is removed");
    }

    @Test
    void sortsByAvailableThenStockThenName() {
        StockListModel<String> model = filled();
        assertEquals(List.of("iron", "brass", "cog", "copper"), keys(model.visible()));
    }

    @Test
    void sortsByNameCaseInsensitively() {
        StockListModel<String> model = filled();
        assertTrue(model.setSort(TerminalSort.NAME));
        assertFalse(model.setSort(TerminalSort.NAME), "setting the same order changes nothing");
        assertEquals(List.of("brass", "cog", "copper", "iron"), keys(model.visible()));
        assertEquals(TerminalSort.AMOUNT, TerminalSort.NAME.next(), "the button cycles back");
    }

    @Test
    void searchMatchesNameAndModId() {
        StockListModel<String> model = filled();
        model.setQuery("ingot");
        assertEquals(List.of("iron", "brass", "copper"), keys(model.visible()));
        model.setQuery("@create");
        assertEquals(List.of("brass", "cog"), keys(model.visible()));
        model.setQuery("@create ingot");
        assertEquals(List.of("brass"), keys(model.visible()), "every token must match");
        model.setQuery("   ");
        assertEquals(4, model.visible().size(), "a blank query keeps everything");
    }

    @Test
    void inStockOnlyHidesFullyPromisedEntries() {
        StockListModel<String> model = filled();
        assertTrue(model.setInStockOnly(true));
        assertEquals(List.of("iron", "brass", "cog"), keys(model.visible()));
        model.setInStockOnly(false);
        assertEquals(4, model.visible().size());
    }

    @Test
    void pagingWindowsTheVisibleLines() {
        StockListModel<String> model = new StockListModel<>();
        List<StockLine<String>> lines = new java.util.ArrayList<>();
        for (int i = 0; i < 11; i++)
            lines.add(line("item" + i, 100 - i, 100 - i, "Item " + (char) ('A' + i), "minecraft"));
        model.replaceAll(lines);

        assertEquals(3, model.rowCount(COLUMNS));
        assertEquals(1, model.maxScrollRow(COLUMNS, ROWS));
        assertEquals(1, model.clampScrollRow(5, COLUMNS, ROWS), "scrolling stops at the last row");
        assertEquals(0, model.clampScrollRow(-3, COLUMNS, ROWS));
        assertEquals(List.of("item0", "item1", "item2", "item3", "item4", "item5", "item6", "item7"),
                keys(model.window(0, COLUMNS, ROWS)));
        assertEquals(List.of("item4", "item5", "item6", "item7", "item8", "item9", "item10"),
                keys(model.window(1, COLUMNS, ROWS)), "the last window may be shorter than the grid");
        assertEquals(keys(model.window(1, COLUMNS, ROWS)), keys(model.window(9, COLUMNS, ROWS)),
                "an out-of-range scroll row is clamped");
    }

    @Test
    void emptyModelPagesWithoutFailing() {
        StockListModel<String> model = new StockListModel<>();
        assertEquals(0, model.rowCount(COLUMNS));
        assertEquals(0, model.maxScrollRow(COLUMNS, ROWS));
        assertTrue(model.window(3, COLUMNS, ROWS).isEmpty());
        assertEquals(Optional.empty(), model.find(null));
    }

    @Test
    void searchAndFilterNarrowTogether() {
        StockListModel<String> model = filled();
        model.setInStockOnly(true);
        model.setQuery("ingot");
        assertEquals(List.of("iron", "brass"), keys(model.visible()));
    }
}
