package dev.wareworks.core.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class SharedInventoriesTest {
    private static final String DOUBLE_CHEST = "double-chest";
    private static final String VAULT = "vault";
    private static final String SINGLE = "single";

    @Test
    void firstLocationIsCanonicalOthersAreAliases() {
        SharedInventories<String, String> shared = new SharedInventories<>();
        SharedInventories.Assignment<String> first = shared.assign("left", DOUBLE_CHEST);
        assertEquals(new SharedInventories.Assignment<>("left", true, Optional.empty()), first);
        SharedInventories.Assignment<String> second = shared.assign("right", DOUBLE_CHEST);
        assertEquals("left", second.canonical(), "the second location shares the first one's counts");
        assertTrue(second.identityChanged());
        assertTrue(shared.isAlias("right"));
        assertFalse(shared.isAlias("left"));
        assertEquals(List.of("left", "right"), shared.sharing("right"));
        assertEquals(Optional.of(DOUBLE_CHEST), shared.identityOf("right"));

        SharedInventories.Assignment<String> again = shared.assign("right", DOUBLE_CHEST);
        assertEquals(new SharedInventories.Assignment<>("left", false, Optional.empty()), again, "unchanged identity");
        assertEquals(2, shared.size());
    }

    @Test
    void removingTheCanonicalLocationPromotesTheOldestAlias() {
        SharedInventories<String, String> shared = new SharedInventories<>();
        shared.assign("a", VAULT);
        shared.assign("b", VAULT);
        shared.assign("c", VAULT);
        assertEquals(Optional.empty(), shared.remove("b"), "removing an alias promotes nobody");
        assertEquals(Optional.of("c"), shared.remove("a"), "the oldest alias takes over");
        assertEquals(Optional.of("c"), shared.canonicalOf("c"));
        assertFalse(shared.isAlias("c"));
        assertEquals(Optional.empty(), shared.remove("c"), "the last location leaves the identity");
        assertEquals(List.of(), shared.sharing("c"));
        assertEquals(Optional.empty(), shared.remove("unknown"));
        assertEquals(0, shared.size());
    }

    @Test
    void anIdentityChangeLeavesTheOldInventory() {
        SharedInventories<String, String> shared = new SharedInventories<>();
        shared.assign("left", DOUBLE_CHEST);
        shared.assign("right", DOUBLE_CHEST);

        // The double chest was split: the canonical half now reads a single chest.
        SharedInventories.Assignment<String> split = shared.assign("left", SINGLE);
        assertEquals("left", split.canonical());
        assertTrue(split.identityChanged());
        assertEquals(Optional.of("right"), split.promoted(), "the other half takes over the old identity");
        assertFalse(shared.isAlias("right"));

        // An alias changing its identity promotes nobody.
        shared.assign("third", SINGLE);
        SharedInventories.Assignment<String> moved = shared.assign("third", VAULT);
        assertEquals(new SharedInventories.Assignment<>("third", true, Optional.empty()), moved);
        assertEquals(List.of("left"), shared.sharing("left"));

        shared.clear();
        assertEquals(0, shared.size());
        assertEquals(Optional.empty(), shared.canonicalOf("left"));
        assertFalse(shared.isAlias("left"));
    }

    @Test
    void aliasCountIsTheLocationsMinusTheInventories() {
        SharedInventories<String, String> shared = new SharedInventories<>();
        assertEquals(0, shared.aliasCount(), "nothing assigned yet");

        shared.assign("left", DOUBLE_CHEST);
        assertEquals(0, shared.aliasCount(), "a canonical location is no alias");
        shared.assign("right", DOUBLE_CHEST);
        shared.assign("single", SINGLE);
        assertEquals(1, shared.aliasCount(), "one of the three locations counts nothing of its own");

        shared.assign("a", VAULT);
        shared.assign("b", VAULT);
        shared.assign("c", VAULT);
        assertEquals(3, shared.aliasCount(), "two vault aliases on top of the chest half");
        assertEquals(6, shared.size());

        shared.remove("a"); // the canonical vault location: an alias is promoted, the count drops by one
        assertEquals(2, shared.aliasCount());
        shared.assign("right", SINGLE); // the chest half now reads the single chest, whose location is canonical
        assertEquals(2, shared.aliasCount(), "it left one identity and joined another as an alias");

        shared.clear();
        assertEquals(0, shared.aliasCount());
    }

    @Test
    void nullArgumentsAreRejected() {
        SharedInventories<String, String> shared = new SharedInventories<>();
        assertThrows(NullPointerException.class, () -> shared.assign(null, VAULT));
        assertThrows(NullPointerException.class, () -> shared.assign("a", null));
        assertThrows(NullPointerException.class, () -> shared.remove(null));
        assertThrows(NullPointerException.class, () -> shared.canonicalOf(null));
    }
}
