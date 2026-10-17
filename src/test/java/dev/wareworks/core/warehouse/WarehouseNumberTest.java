package dev.wareworks.core.warehouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The numbers the "Warehouse Number" display source can show ({@link WarehouseNumber}, issue #32).
 * <p>
 * The case that matters most is {@link #theSavedIndexOfEveryNumberNeverMoves()}: a Display Link saves the number a
 * player picked as an index, so a value moved, removed or put in the middle would turn every board already built into
 * a board that shows something else. A new value goes at the end, and the list below grows with it.
 */
class WarehouseNumberTest {
    /** Every value with the index a world on disk knows it by. Only ever append to this list. */
    private static final List<String> SAVED_ORDER = List.of("ITEMS", "ITEM_TYPES", "FLUID", "LOCATIONS_USED",
            "LOCATIONS_TOTAL", "LOCATIONS_USED_SHARE", "OPEN_REQUESTS", "PRODUCTION_ORDERS", "RULES_BELOW_MINIMUM",
            "RULES_AT_MAXIMUM", "STOPPED_PRODUCTS", "ACCEPTING_PORTS", "COLLECTING_PORTS", "MISALIGNED");

    @Test
    void theSavedIndexOfEveryNumberNeverMoves() {
        List<String> names = new ArrayList<>();
        for (WarehouseNumber number : WarehouseNumber.values()) {
            names.add(number.name());
            assertEquals(names.size() - 1, number.index(), number + " is saved as its position");
        }
        assertEquals(SAVED_ORDER, names, "a new number goes at the end, never in between");
    }

    @Test
    void everySavedIndexReadsBackAsItsNumber() {
        for (WarehouseNumber number : WarehouseNumber.values())
            assertEquals(Optional.of(number), WarehouseNumber.byIndex(number.index()), "index " + number.index());
    }

    /** A link saved by a later build, or edited by hand, picks nothing: the source then writes nothing at all. */
    @Test
    void anIndexNoNumberHasPicksNothing() {
        assertEquals(Optional.empty(), WarehouseNumber.byIndex(-1));
        assertEquals(Optional.empty(), WarehouseNumber.byIndex(WarehouseNumber.values().length));
        assertEquals(Optional.empty(), WarehouseNumber.byIndex(Integer.MAX_VALUE));
        assertEquals(Optional.empty(), WarehouseNumber.byIndex(Integer.MIN_VALUE));
    }

    @Test
    void everyNumberHasALangKeyOfItsOwn() {
        Set<String> keys = new HashSet<>();
        for (WarehouseNumber number : WarehouseNumber.values()) {
            String key = number.langKey();
            assertTrue(key.matches("[a-z_]+"), "a lang key tail is lower case: " + key);
            assertTrue(keys.add(key), "two numbers share the name " + key);
        }
        assertTrue(!keys.contains("shown"), "'shown' is the selector's own title, below the same prefix");
    }

    /** Whole percent, rounded down, so a nearly full warehouse never reads full, and 0 for a warehouse with nothing. */
    @Test
    void aShareIsRoundedDownAndBounded() {
        assertEquals(0, WarehouseNumber.share(0, 0), "no locations at all");
        assertEquals(0, WarehouseNumber.share(3, 0), "no whole to be a share of");
        assertEquals(0, WarehouseNumber.share(0, 30));
        assertEquals(33, WarehouseNumber.share(1, 3));
        assertEquals(66, WarehouseNumber.share(2, 3), "two thirds are 66, not 67");
        assertEquals(99, WarehouseNumber.share(199, 200), "99.5 % is not full yet");
        assertEquals(100, WarehouseNumber.share(30, 30));
        assertEquals(100, WarehouseNumber.share(31, 30), "never above 100");
        assertEquals(0, WarehouseNumber.share(-1, 30), "never below 0");
        assertEquals(50, WarehouseNumber.share(Integer.MAX_VALUE, 2L * Integer.MAX_VALUE), "no overflow");
    }
}
