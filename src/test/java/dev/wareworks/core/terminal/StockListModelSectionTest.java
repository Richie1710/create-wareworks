package dev.wareworks.core.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Package mode's two sections of the terminal list (issue #40, owner decision E5): the address rows first, the loose
 * stock from before the switch after them, each in the chosen order; and with no section set the list is unchanged.
 */
class StockListModelSectionTest {
    private static final Set<String> LOOSE = Set.of("iron", "dirt");

    private static StockListModel<String> filled() {
        StockListModel<String> model = new StockListModel<>();
        model.replaceAll(List.of(new StockLine<>("iron", 900, 900, "Iron Ingot", "minecraft"),
                new StockLine<>("north", 3, 3, "Packages → Base North", "create"),
                new StockLine<>("dirt", 5, 5, "Dirt", "minecraft"),
                new StockLine<>("mine", 12, 12, "Packages → Mine", "create")));
        return model;
    }

    private static List<String> keys(List<StockLine<String>> lines) {
        return lines.stream().map(StockLine::key).toList();
    }

    @Test
    void withoutASectionTheListIsOneSortedList() {
        StockListModel<String> model = filled();
        model.setSort(TerminalSort.AMOUNT);
        assertEquals(List.of("iron", "mine", "dirt", "north"), keys(model.visible()));
        assertEquals(-1, model.firstTrailing());
    }

    @Test
    void theLooseStockComesAfterEveryAddressRowWhateverTheSort() {
        StockListModel<String> model = filled();
        model.setTrailingSection(LOOSE::contains);
        model.setSort(TerminalSort.AMOUNT);
        assertEquals(List.of("mine", "north", "iron", "dirt"), keys(model.visible()),
                "each section keeps the chosen order");
        assertEquals(2, model.firstTrailing());
        model.setSort(TerminalSort.NAME);
        assertEquals(List.of("north", "mine", "dirt", "iron"), keys(model.visible()),
                "Base North before Mine by name, then the loose section by name");
    }

    @Test
    void theSectionFollowsTheSearch() {
        StockListModel<String> model = filled();
        model.setTrailingSection(LOOSE::contains);
        model.setQuery("packages");
        assertEquals(-1, model.firstTrailing(), "no loose line is visible");
        model.setQuery("iron");
        assertEquals(List.of("iron"), keys(model.visible()));
        assertEquals(0, model.firstTrailing());
    }

    @Test
    void removingTheSectionRestoresOneList() {
        StockListModel<String> model = filled();
        model.setSort(TerminalSort.AMOUNT);
        List<String> before = keys(model.visible());
        model.setTrailingSection(LOOSE::contains);
        model.setTrailingSection(null);
        assertEquals(before, keys(model.visible()));
        assertEquals(-1, model.firstTrailing());
    }

    @Test
    void aGroupCountKeepsItsSummaryAndTheEightArgumentFormHasNone() {
        PackageGroupSummary<String> summary = new PackageGroupSummary<>(3, List.of(), 0, false);
        StockCount<String> group = StockCount.group("north", 3, 2, summary);
        assertEquals(Optional.of(summary), group.group());
        assertEquals(true, group.isGroup());
        assertEquals(Optional.empty(), new StockCount<>("iron", 1, 1, false, 0L, Optional.empty(), 0L, -1L).group());
    }
}
