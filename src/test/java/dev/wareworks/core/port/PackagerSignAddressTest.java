package dev.wareworks.core.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The sign rule of a Create Packager, mirrored (M26, issue #18): the three loops of
 * {@code PackagerBlockEntity#updateSignAddress} as pure functions, plus the display cut that is the one place where a
 * Wareworks surface is allowed to disagree with the box.
 * <p>
 * This is a <b>replication</b> test, so it pins the surprises rather than the obvious: that the <b>last</b> sign wins
 * and not the first, that the <b>front</b> face wins over the back, and that a line of no-break spaces is a real
 * address because Create asks {@code isBlank()} and trims with {@code trim()}. The assertion that the replication is
 * still faithful at run time is the GameTest {@code packagesignaddressmatchescreate}, which compares this against
 * {@code PackageItem.getAddress} of a box a real Packager produced; nothing here can see a Create change.
 */
class PackagerSignAddressTest {
    @Test
    void aSignTextIsItsNonBlankLinesJoinedWithSingleSpaces() {
        assertEquals("Base North", PackagerSignAddress.of(List.of("Base", "North", "", "")));
        // Each line is trimmed on both ends and the join adds exactly one space, whatever the player typed.
        assertEquals("Base North", PackagerSignAddress.of(List.of("  Base  ", "   North", "", "")));
        // A blank line in the middle is skipped entirely rather than becoming a double space.
        assertEquals("Base North", PackagerSignAddress.of(List.of("Base", "   ", "North", "")));
        // Spaces inside a line are the player's text and are kept; only the ends are trimmed.
        assertEquals("Base  North Depot", PackagerSignAddress.of(List.of(" Base  North ", "Depot", "", "")));
        assertEquals("One", PackagerSignAddress.of(List.of("", "", "", "One")));
    }

    /** Every input that spells nothing gives exactly {@link PackagerSignAddress#NONE}, and nothing throws. */
    @Test
    void nothingAtAllIsAlwaysTheSameEmptyAddress() {
        assertSame(PackagerSignAddress.NONE, PackagerSignAddress.of(null));
        assertSame(PackagerSignAddress.NONE, PackagerSignAddress.of(List.of()));
        assertEquals(PackagerSignAddress.NONE, PackagerSignAddress.of(List.of("", "", "", "")));
        assertEquals(PackagerSignAddress.NONE, PackagerSignAddress.of(List.of(" ", "\t", "\n", "   ")));
        assertEquals(PackagerSignAddress.NONE, PackagerSignAddress.of(Arrays.asList(null, null, null, null)));
        assertEquals("Base", PackagerSignAddress.of(Arrays.asList(null, "Base", null, " ")));
        assertTrue(PackagerSignAddress.NONE.isEmpty(), "a caller tests for 'no address' with isEmpty()");
    }

    /**
     * {@code trim()}, not {@code strip()}: Create trims each line with {@link String#trim()}, which stops at
     * {@code U+0020}, while "is this line blank" is {@link String#isBlank()}, which is Unicode-aware and does not count
     * a no-break space as whitespace. A sign of no-break spaces therefore really does address a box, and a tidier
     * implementation here would quietly disagree with the box for ever.
     */
    @Test
    void aNoBreakSpaceIsAnAddressBecauseCreateTrimsAndDoesNotStrip() {
        String noBreakSpace = " ";
        assertFalse(noBreakSpace.isBlank(), "the premise: Java does not count U+00A0 as whitespace");
        assertEquals(noBreakSpace, PackagerSignAddress.of(List.of(noBreakSpace, "", "", "")));
        assertEquals(noBreakSpace + " Base", PackagerSignAddress.of(List.of(noBreakSpace, "Base", "", "")));
        // And it is not trimmed off the end of a line either, for the same reason.
        assertEquals("Base" + noBreakSpace, PackagerSignAddress.of(List.of("Base" + noBreakSpace, "", "", "")));
    }

    /** The front face wins whenever it spells anything at all; the back is only ever the fallback. */
    @Test
    void theFrontOfASignWinsOverItsBack() {
        List<String> front = List.of("Base North", "", "", "");
        List<String> back = List.of("Base South", "", "", "");
        assertEquals("Base North", PackagerSignAddress.ofSign(front, back));
        assertEquals("Base South", PackagerSignAddress.ofSign(List.of("", " ", "", ""), back));
        assertEquals("Base South", PackagerSignAddress.ofSign(null, back));
        assertEquals("Base North", PackagerSignAddress.ofSign(front, null));
        assertEquals(PackagerSignAddress.NONE, PackagerSignAddress.ofSign(List.of("", "", "", ""), null));
    }

    /**
     * The <b>last</b> non-blank neighbour wins, not the first: {@code updateSignAddress} assigns in a loop over
     * {@code Direction.values()} and never breaks out of it. First is the natural guess, and a warehouse with a sign
     * above and below its Packager would then be addressed to the wrong base.
     */
    @Test
    void theLastSignAroundThePackagerWins() {
        // Direction.values() order: DOWN, UP, NORTH, SOUTH, WEST, EAST.
        assertEquals("East", PackagerSignAddress.lastOf(List.of("Down", "", "", "", "", "East")));
        assertEquals("Up", PackagerSignAddress.lastOf(List.of("Down", "Up", "", "", "", "")));
        assertEquals("Only", PackagerSignAddress.lastOf(List.of("", "", "Only", "", "", "")));
        assertEquals(PackagerSignAddress.NONE, PackagerSignAddress.lastOf(List.of("", " ", "", "", "", "")));
        assertSame(PackagerSignAddress.NONE, PackagerSignAddress.lastOf(null));
        assertEquals("Base", PackagerSignAddress.lastOf(Arrays.asList(null, "Base", null)));
    }

    /**
     * The display cut: an address that fits is drawn as it is, and one that does not is cut to
     * {@link PackagerSignAddress#DISPLAY_LENGTH} characters with {@link PackagerSignAddress#wouldShorten} saying so, so
     * the surface can mark it.
     */
    @Test
    void anAddressTooLongForARowIsCutAndSaysSo() {
        assertEquals(25, PackagerSignAddress.DISPLAY_LENGTH, "Create's own address box holds 25 characters");
        String fits = "a".repeat(PackagerSignAddress.DISPLAY_LENGTH);
        assertSame(fits, PackagerSignAddress.shorten(fits), "an address that fits is not even copied");
        assertFalse(PackagerSignAddress.wouldShorten(fits));
        String tooLong = fits + "bbbb";
        assertTrue(PackagerSignAddress.wouldShorten(tooLong));
        assertEquals(fits, PackagerSignAddress.shorten(tooLong));
        assertEquals(PackagerSignAddress.NONE, PackagerSignAddress.shorten(null));
        assertFalse(PackagerSignAddress.wouldShorten(null));
        assertEquals("", PackagerSignAddress.shorten(""));
    }

    /** A cut never leaves a ragged edge: no trailing whitespace, and never half of a surrogate pair. */
    @Test
    void aCutNeverEndsInWhitespaceOrHalfACharacter() {
        String cutAtASpace = "a".repeat(PackagerSignAddress.DISPLAY_LENGTH - 1) + "   tail";
        assertEquals("a".repeat(PackagerSignAddress.DISPLAY_LENGTH - 1),
                PackagerSignAddress.shorten(cutAtASpace));
        // A no-break space counts too, which String#strip would keep: it would draw as a gap before the ellipsis.
        String cutAtANoBreakSpace = "b".repeat(PackagerSignAddress.DISPLAY_LENGTH - 1) + " tail";
        assertEquals("b".repeat(PackagerSignAddress.DISPLAY_LENGTH - 1),
                PackagerSignAddress.shorten(cutAtANoBreakSpace));
        // The cut would land between the two halves of this code point, so it drops the pair instead of splitting it.
        String grin = "😀";
        String cutInsideAPair = "c".repeat(PackagerSignAddress.DISPLAY_LENGTH - 1) + grin;
        String shortened = PackagerSignAddress.shorten(cutInsideAPair);
        assertEquals(PackagerSignAddress.DISPLAY_LENGTH - 1, shortened.length());
        assertFalse(Character.isHighSurrogate(shortened.charAt(shortened.length() - 1)));
        // A whole pair that ends exactly on the bound is kept whole.
        String endsOnThePair = "d".repeat(PackagerSignAddress.DISPLAY_LENGTH - 2) + grin;
        assertEquals(endsOnThePair, PackagerSignAddress.shorten(endsOnThePair + "more"));
    }

    /**
     * The three loops compose into what a Packager really ends up with, and an address that came out of them is always
     * something a surface can draw: never {@code null}, never blank-but-present, never padded.
     */
    @Test
    void theThreeLoopsComposeIntoOneDrawableAddress() {
        List<String> perNeighbour = new ArrayList<>();
        perNeighbour.add(PackagerSignAddress.ofSign(List.of("  ", "", "", ""), List.of("Old", "Depot", "", "")));
        perNeighbour.add(PackagerSignAddress.ofSign(List.of("", "", "", ""), List.of("", "", "", "")));
        perNeighbour.add(PackagerSignAddress.ofSign(List.of(" Base ", " North ", "", ""), List.of("Ignored", "", "",
                "")));
        String address = PackagerSignAddress.lastOf(perNeighbour);
        assertEquals("Base North", address);
        assertFalse(address.isBlank());
        assertEquals(address.trim(), address);
        assertSame(address, PackagerSignAddress.shorten(address));
    }
}
