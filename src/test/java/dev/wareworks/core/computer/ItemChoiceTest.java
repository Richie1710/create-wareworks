package dev.wareworks.core.computer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/** Which listed item a computer's argument means (issue #9, ADR-074). */
class ItemChoiceTest {
    private static final Map<String, String> PRINTS = Map.of("log", "aaaa0001", "book-a", "bbbb0001", "book-b",
            "bbbb0002", "sword-named", "cccc0001");
    private static final Function<String, String> FINGERPRINTS = PRINTS::get;
    private static final List<ItemChoice.Candidate<String>> LISTED = List.of(
            new ItemChoice.Candidate<>("log", "minecraft:oak_log", false),
            new ItemChoice.Candidate<>("book-a", "minecraft:enchanted_book", true),
            new ItemChoice.Candidate<>("book-b", "minecraft:enchanted_book", true),
            new ItemChoice.Candidate<>("sword-named", "minecraft:iron_sword", true));

    private static ItemChoice<String> choose(String id, String fingerprint) {
        return ItemChoice.choose(LISTED, id, Optional.ofNullable(fingerprint), FINGERPRINTS);
    }

    @Test
    void aPlainIdFindsThePlainKey() {
        assertEquals(new ItemChoice.Found<>("log"), choose("minecraft:oak_log", null));
    }

    @Test
    void aPlainIdPrefersTheKeyWithoutComponents() {
        List<ItemChoice.Candidate<String>> both = List.of(
                new ItemChoice.Candidate<>("named", "minecraft:oak_log", true),
                new ItemChoice.Candidate<>("plain", "minecraft:oak_log", false));
        assertEquals(new ItemChoice.Found<>("plain"),
                ItemChoice.choose(both, "minecraft:oak_log", Optional.empty(), key -> "00000000"));
    }

    @Test
    void theOnlyVariantIsFoundByItsId() {
        assertEquals(new ItemChoice.Found<>("sword-named"), choose("minecraft:iron_sword", null));
    }

    @Test
    void severalVariantsAreAmbiguous() {
        assertEquals(new ItemChoice.Ambiguous<String>(2), choose("minecraft:enchanted_book", null));
    }

    @Test
    void aFingerprintPicksTheVariantIgnoringCase() {
        assertEquals(new ItemChoice.Found<>("book-b"), choose("minecraft:enchanted_book", "bbbb0002"));
        assertEquals(new ItemChoice.Found<>("book-a"), choose("minecraft:enchanted_book", "BBBB0001"));
    }

    @Test
    void aFingerprintOfAnotherItemOrNoneMatchingIsAbsent() {
        assertInstanceOf(ItemChoice.Absent.class, choose("minecraft:enchanted_book", "aaaa0001"));
        assertInstanceOf(ItemChoice.Absent.class, choose("minecraft:oak_log", "ffffffff"));
    }

    @Test
    void anUnlistedItemIsAbsent() {
        assertInstanceOf(ItemChoice.Absent.class, choose("minecraft:diamond", null));
        assertInstanceOf(ItemChoice.Absent.class, ItemChoice.choose(List.<ItemChoice.Candidate<String>>of(),
                "minecraft:oak_log", Optional.empty(), FINGERPRINTS));
    }
}
