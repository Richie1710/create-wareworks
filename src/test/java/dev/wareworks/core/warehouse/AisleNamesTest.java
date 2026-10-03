package dev.wareworks.core.warehouse;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.wareworks.core.address.StorageAddress;

/**
 * The name a player gives an aisle ({@link AisleName}, {@link AisleNames}, M25, issue #15): the whole spelling rule
 * and the whole table, which is the whole feature at this layer — nothing is saved, synced or drawn yet.
 * <p>
 * What these cases are really defending is a single property: <b>the name in the save, the name in the packet and the
 * name on the goggles are the same string</b>. That holds only if sanitising is total (every input is a legal name)
 * and idempotent (sanitising a name again changes nothing), because those two are what let the same text pass through
 * an item, a tag and a tooltip without any of them having to agree on anything else. So the two tests that would hurt
 * most if they went are {@link #sanitisingTwiceChangesNothing()} and {@link #nothingASaveOrAPacketCouldHoldThrows()}.
 */
class AisleNamesTest {
    /** Exactly {@link AisleName#MAX_LENGTH} characters, with a space in it so the collapse rule is exercised too. */
    private static final String EXACTLY_MAX = "Erzlager Nordost";
    /** One character more, so the cut has something to take. */
    private static final String ONE_TOO_LONG = "Erzlager Nordwest";

    /**
     * Everything a save, a packet, a command-set custom name or a hand-edited world could plausibly put in front of
     * the sanitiser. Used by the total-ness and idempotence cases, which are the two that have to hold for all input.
     */
    private static final String[] HOSTILE = {
            null, "", " ", " ", "    ", "\t\n\r", "\u0000", "\u007F", "§", "§§§§§§§§§§§§§§§§",
            "§cOres", "Ores", EXACTLY_MAX, ONE_TOO_LONG, "  padded  ", "a   b", "\n\nOre\nLager\n\n",
            "😀", "\uD83D", "\uDE00", "\uD83D\uD83D\uD83D", "Erzlager Nordos😀",
            "Erzlager Nordo😀", "0123456789012345😀", "a  b", "　Ore　Lager　",
            "x".repeat(1000), ("ä b ".repeat(500)), "A-03-07R", "100%", "\"quoted\"", "§r§lbold",
    };

    private static AisleNames named(Map<Character, String> entries) {
        return new AisleNames(entries);
    }

    // ---------------------------------------------------------------- AisleName: the spelling rule

    @Test
    void theSectionSignIsStrippedSoNoNameCanCarryFormatting() {
        // Vanilla's own rule, mirrored: isAllowedChatCharacter rejects the section sign itself and nothing else about
        // a colour code, so the sign goes and the stray code letter stays. That is the point - a name can never
        // re-colour a goggle line or a display board, and it also cannot smuggle in a reset.
        assertEquals("cOres", AisleName.sanitize("§cOres"));
        assertEquals("rlbold", AisleName.sanitize("§r§lbold"));
        assertEquals("", AisleName.sanitize("§§§§§§§§§§§§§§§§"));
    }

    @Test
    void controlCharactersAndNewlinesAreStrippedNotTurnedIntoSpaces() {
        // filterText(text, false) drops them outright, so two words separated only by a newline really do run
        // together. Asserted rather than smoothed over: an anvil cannot produce a newline, so the only caller that
        // can is a command or a hand-edited save, and matching vanilla exactly is worth more than guessing a space.
        assertEquals("OreLager", AisleName.sanitize("Ore\nLager"));
        assertEquals("", AisleName.sanitize("\t\n\r"));
        assertEquals("", AisleName.sanitize("\u0000"));
        assertEquals("", AisleName.sanitize("\u007F"));
    }

    @Test
    void whitespaceIsTrimmedOffBothEndsAndInternalRunsCollapse() {
        assertEquals("Erze und Kohle", AisleName.sanitize("  Erze   und   Kohle  "));
        assertEquals("padded", AisleName.sanitize("  padded  "));
        assertEquals("a b", AisleName.sanitize("a   b"));
    }

    @Test
    void theGamesWiderWhitespaceCountsSoAnInvisibleNameIsNoName() {
        // A no-break space survives both filterText and String.strip(), so without isSpaceChar a hand-edited save
        // could hold a sixteen-character name that draws as nothing and still reports itself as present.
        assertEquals("", AisleName.sanitize(" "));
        assertEquals("", AisleName.sanitize("    "));
        assertEquals("a b", AisleName.sanitize("a  b"));
        assertEquals("Ore Lager", AisleName.sanitize("　Ore　Lager　"));
        assertTrue(named(Map.of('A', "  ")).isEmpty(), "an invisible name is not stored at all");
    }

    @Test
    void exactlyTheMaximumIsKeptAndOneMoreIsCut() {
        assertEquals(AisleName.MAX_LENGTH, EXACTLY_MAX.length());
        assertEquals(EXACTLY_MAX, AisleName.sanitize(EXACTLY_MAX));
        assertFalse(AisleName.wouldCut(EXACTLY_MAX), "a name that fits is not reported as shortened");

        assertEquals(AisleName.MAX_LENGTH + 1, ONE_TOO_LONG.length());
        assertEquals("Erzlager Nordwes", AisleName.sanitize(ONE_TOO_LONG));
        assertEquals(AisleName.MAX_LENGTH, AisleName.sanitize(ONE_TOO_LONG).length());
        assertTrue(AisleName.wouldCut(ONE_TOO_LONG));
    }

    @Test
    void wouldCutReportsTheLengthAndNothingElse() {
        // It drives one action-bar line, "Shortened to ...", so it must fire exactly when the player's own readable
        // text lost something off the end - never for a section sign or a double space they could not see anyway.
        assertFalse(AisleName.wouldCut(null));
        assertFalse(AisleName.wouldCut(""));
        assertFalse(AisleName.wouldCut("§cOres"), "a stripped section sign is not a shortening a player can perceive");
        assertFalse(AisleName.wouldCut("  Erze   und   Kohle  "), "nor is a collapsed run of spaces");
        assertFalse(AisleName.wouldCut("§".repeat(100)), "nothing drawable at all is not a shortening either");
        assertTrue(AisleName.wouldCut("x".repeat(AisleName.MAX_LENGTH + 1)));
        assertTrue(AisleName.wouldCut("§" + "x".repeat(AisleName.MAX_LENGTH + 1)),
                "and the length that counts is what is left after the strip");
    }

    @Test
    void aCutNeverLeavesHalfACodePointOrARaggedEdge() {
        // The cut is by characters, so it can land between a surrogate pair or just after a space. Both would be
        // stored and drawn, so both are trimmed back - and the result is still a name sanitise() would return.
        String cutThroughAPair = AisleName.sanitize("0123456789012345😀");
        assertEquals("0123456789012345", cutThroughAPair);

        String spaceThenPair = AisleName.sanitize("Erzlager Nordos😀");
        assertFalse(spaceThenPair.endsWith(" "), spaceThenPair + " must not end with the space the cut exposed");
        assertEquals("Erzlager Nordos", spaceThenPair);

        assertEquals("Erzlager Nordo😀", AisleName.sanitize("Erzlager Nordo😀"),
                "a pair that fits whole is kept whole");
        assertEquals("", AisleName.sanitize("\uD83D"), "and a lone high surrogate is not a name");
        assertEquals("", AisleName.sanitize("\uD83D\uD83D\uD83D"));
    }

    @Test
    void theEmptyStringAndNullBothMeanNoName() {
        assertEquals(AisleName.NONE, AisleName.sanitize(null));
        assertEquals(AisleName.NONE, AisleName.sanitize(""));
        assertEquals("", AisleName.NONE);

        AisleNames names = new AisleNames();
        assertTrue(names.set('A', null), "a valid letter is still a valid letter");
        assertEquals(Optional.empty(), names.nameOf('A'));
        assertTrue(names.set('A', ""));
        assertEquals(Optional.empty(), names.nameOf('A'), "a blank name is absence, not an empty string");
        assertTrue(names.isEmpty());
    }

    @Test
    void sanitisingTwiceChangesNothing() {
        // The property the whole feature rests on: the save, the packet and the tooltip can only agree on a name if
        // re-sanitising it is a no-op, because each of them does it independently.
        for (String raw : HOSTILE) {
            String once = AisleName.sanitize(raw);
            String twice = AisleName.sanitize(once);
            assertEquals(once, twice, "not idempotent for " + describe(raw));
            assertSame(once, twice, "a clean name must be returned unchanged, without a copy: " + describe(raw));
            assertTrue(once.length() <= AisleName.MAX_LENGTH, describe(raw) + " produced " + once.length() + " chars");
        }
    }

    @Test
    void nothingASaveOrAPacketCouldHoldThrows() {
        for (String raw : HOSTILE) {
            assertDoesNotThrow(() -> AisleName.sanitize(raw), "sanitize " + describe(raw));
            assertDoesNotThrow(() -> AisleName.wouldCut(raw), "wouldCut " + describe(raw));
            assertDoesNotThrow(() -> {
                AisleNames names = new AisleNames();
                names.set('A', raw);
                names.nameOf('A');
                names.entries();
                names.clear('A');
            }, "the table with " + describe(raw));
        }
        // And every char value as a letter, which is what a tag holding a short or a string's first char can be.
        assertDoesNotThrow(() -> {
            AisleNames names = new AisleNames();
            for (char letter = Character.MIN_VALUE;; letter++) {
                names.set(letter, "Ores");
                names.nameOf(letter);
                names.hasName(letter);
                names.clear(letter);
                names.rename(letter, 'A');
                names.rename('A', letter);
                if (letter == Character.MAX_VALUE)
                    break;
            }
        });
        assertDoesNotThrow(() -> new AisleNames((Map<Character, String>) null), "an absent tag is an empty table");
        assertDoesNotThrow(() -> new AisleNames(mapWithNulls()), "and so is a tag whose entries went missing");
    }

    // ---------------------------------------------------------------- AisleNames: the table

    @Test
    void onlyAnAisleLetterCanCarryAName() {
        AisleNames names = new AisleNames();
        for (char letter : new char[] { 'a', 'z', '0', '-', ' ', 'Ä', '[', '@', '\u0000', Character.MAX_VALUE }) {
            assertFalse(names.set(letter, "Ores"), "'" + letter + "' is not an aisle letter");
            assertEquals(Optional.empty(), names.nameOf(letter));
        }
        assertTrue(names.isEmpty(), "a refused letter changed nothing");

        assertTrue(names.set(StorageAddress.FIRST_AISLE, "Ores"));
        assertTrue(names.set(StorageAddress.LAST_AISLE, "Metals"));
        assertEquals(2, names.size());
    }

    @Test
    void aNameIsSetThenReadThenCleared() {
        AisleNames names = new AisleNames();
        assertTrue(names.isEmpty());
        assertFalse(names.hasName('A'));

        assertTrue(names.set('A', "  Ores  "));
        assertEquals(Optional.of("Ores"), names.nameOf('A'), "stored sanitised, not as typed");
        assertTrue(names.hasName('A'));
        assertFalse(names.isEmpty());

        assertTrue(names.set('A', "Iron Ores"), "setting again replaces");
        assertEquals(Optional.of("Iron Ores"), names.nameOf('A'));

        assertTrue(names.clear('A'), "there was a name to take off");
        assertEquals(Optional.empty(), names.nameOf('A'));
        assertTrue(names.isEmpty());
        assertFalse(names.clear('A'), "and clearing again says so, so the player is not told twice");
        assertFalse(names.clear('b'), "nor is an invalid letter ever reported as cleared");
    }

    @Test
    void aLetterThatMovedTakesItsNameWithIt() {
        AisleNames names = new AisleNames();
        names.set('A', "Ores");
        names.set('C', "Fuel");

        names.rename('A', 'D');
        assertEquals(Optional.empty(), names.nameOf('A'));
        assertEquals(Optional.of("Ores"), names.nameOf('D'));
        assertEquals(Optional.of("Fuel"), names.nameOf('C'), "an uninvolved letter is untouched");
        assertEquals(2, names.size());
    }

    @Test
    void aLetterThatMovedOntoATakenOneSwapsSoNoNameIsEverLost() {
        AisleNames names = new AisleNames();
        names.set('A', "Ores");
        names.set('D', "Metals");

        names.rename('A', 'D');
        assertEquals(Optional.of("Metals"), names.nameOf('A'));
        assertEquals(Optional.of("Ores"), names.nameOf('D'));

        // Reversible, which is the whole reason it is a swap: scrolling the value box back restores exactly what the
        // player had, and a letter change on its own can never destroy a name.
        names.rename('A', 'D');
        assertEquals(Optional.of("Ores"), names.nameOf('A'));
        assertEquals(Optional.of("Metals"), names.nameOf('D'));
    }

    @Test
    void anUnnamedLetterSwapsJustTheSameWayRound() {
        AisleNames names = new AisleNames();
        names.set('D', "Metals");

        names.rename('A', 'D');
        assertEquals(Optional.of("Metals"), names.nameOf('A'));
        assertEquals(Optional.empty(), names.nameOf('D'));
        assertEquals(1, names.size(), "a swap moves names, it does not make or drop one");
    }

    @Test
    void renamingToTheSameLetterOrToNoLetterAtAllDoesNothing() {
        AisleNames names = new AisleNames();
        names.set('A', "Ores");
        names.set('B', "Metals");
        Map<Character, String> before = names.entries();

        names.rename('A', 'A');
        names.rename('A', 'a');
        names.rename('a', 'A');
        names.rename('0', '-');
        names.rename('Z', 'Z');
        assertEquals(before, names.entries());
    }

    @Test
    void aTableCannotHoldMoreNamesThanThereAreAisleLetters() {
        // Thirty entries offered, twenty-six letters in existence: the bound is the shape of the key, not a cap that
        // has to be enforced, which is why no save can make this table grow.
        Map<Character, String> offered = new LinkedHashMap<>();
        for (char letter = StorageAddress.FIRST_AISLE; letter <= StorageAddress.LAST_AISLE; letter++)
            offered.put(letter, "Aisle " + letter);
        offered.put('a', "lower case");
        offered.put('0', "digit");
        offered.put('-', "dash");
        offered.put('Ä', "umlaut");
        assertEquals(30, offered.size());

        AisleNames names = named(offered);
        assertEquals(StorageAddress.AISLE_COUNT, names.size());
        assertEquals(26, StorageAddress.AISLE_COUNT);
        assertEquals(Optional.of("Aisle A"), names.nameOf('A'));
        assertEquals(Optional.of("Aisle Z"), names.nameOf('Z'));
        assertEquals(Optional.empty(), names.nameOf('a'));
    }

    @Test
    void theTableReSanitisesWhatItWasHandedAndSkipsTheRestEntryByEntry() {
        Map<Character, String> saved = new LinkedHashMap<>();
        saved.put('A', "§cOres");
        saved.put('B', "  Metals  ");
        saved.put('C', ONE_TOO_LONG);
        saved.put('D', " ");
        saved.put('e', "lower case");
        saved.put('F', null);
        saved.put('G', "Fuel");

        AisleNames names = named(saved);
        assertEquals(Optional.of("cOres"), names.nameOf('A'));
        assertEquals(Optional.of("Metals"), names.nameOf('B'));
        assertEquals(Optional.of("Erzlager Nordwes"), names.nameOf('C'));
        assertEquals(Optional.empty(), names.nameOf('D'), "an invisible name is skipped");
        assertEquals(Optional.empty(), names.nameOf('e'), "an impossible letter is skipped");
        assertEquals(Optional.empty(), names.nameOf('F'), "a missing name is skipped");
        assertEquals(Optional.of("Fuel"), names.nameOf('G'), "and one bad entry never costs a good one");
        assertEquals(4, names.size());
    }

    @Test
    void entriesComeOutInLetterOrderAsAnUnmodifiableSnapshot() {
        AisleNames names = new AisleNames();
        names.set('C', "Fuel");
        names.set('A', "Ores");
        names.set('B', "Metals");

        assertEquals(List.of('A', 'B', 'C'), List.copyOf(names.entries().keySet()),
                "letter order, because persistence and the goggle line both need the same build to read the same way");
        assertEquals(List.of("Ores", "Metals", "Fuel"), List.copyOf(names.entries().values()));

        Map<Character, String> snapshot = names.entries();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put('D', "Stone"));
        names.set('D', "Stone");
        assertEquals(3, snapshot.size(), "a snapshot does not follow later changes");
        assertEquals(4, names.entries().size());
    }

    @Test
    void aWarehouseWithNoNamesCostsNothing() {
        AisleNames names = new AisleNames();
        assertTrue(names.isEmpty());
        assertEquals(0, names.size());
        assertTrue(names.entries().isEmpty());

        names.set('A', "Ores");
        assertFalse(names.isEmpty());
        names.clearAll();
        assertTrue(names.isEmpty(), "and it is the same nothing after the names are gone again");
        assertTrue(names.entries().isEmpty());
    }

    @Test
    void oneTableCanBeCopiedOntoAnother() {
        AisleNames source = new AisleNames();
        source.set('A', "Ores");
        source.set('B', "Metals");

        AisleNames target = new AisleNames();
        target.set('Z', "Scrap");
        target.copyFrom(source);
        assertEquals(source.entries(), target.entries());
        assertEquals(Optional.empty(), target.nameOf('Z'), "a copy replaces, it does not merge");

        target.set('C', "Fuel");
        assertEquals(Optional.empty(), source.nameOf('C'), "and the source is left alone");

        target.copyFrom(null);
        assertTrue(target.isEmpty());
    }

    private static Map<Character, String> mapWithNulls() {
        Map<Character, String> entries = new HashMap<>();
        entries.put(null, "no letter");
        entries.put('A', null);
        return entries;
    }

    private static String describe(String raw) {
        if (raw == null)
            return "null";
        StringBuilder described = new StringBuilder(raw.length() + 2).append('"');
        raw.codePoints().limit(40).forEach(point -> {
            if (point >= ' ' && point < 127)
                described.appendCodePoint(point);
            else
                described.append(String.format("\\u%04X", point));
        });
        return described.append('"').toString();
    }
}
