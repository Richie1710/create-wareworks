package dev.wareworks.core.port;

import java.util.List;

import org.jetbrains.annotations.Nullable;

/**
 * The address a Create Packager reads off the signs around it (M26, issue #18), as three pure functions — one per loop
 * of Create's own code, so a Wareworks surface can say what the <b>next</b> box will be addressed to without asking the
 * Packager, and without being able to disagree with it.
 * <p>
 * <b>Why this is mirrored rather than called.</b> {@code PackagerBlockEntity#signBasedAddress} is a public field, but it
 * is only refreshed immediately before a send ({@code PackagerBlockEntity.java:292-293,355-356}), so it is stale for as
 * long as the Packager is idle — which is exactly when a player looks at the door. The rule is therefore re-applied to
 * the signs that are there right now. The price is that it can drift from Create: the GameTest
 * {@code packagesignaddressmatchescreate} compares this against {@code PackageItem.getAddress} of a box a real Packager
 * produced, which is what makes a divergence impossible to ship silently, and {@code docs/dependencies.md} records the
 * rule as mirrored.
 * <p>
 * <b>The three loops, in Create's order</b> ({@code PackagerBlockEntity#updateSignAddress:538-569}):
 * <ol>
 * <li>{@link #of(List)} is the innermost one: every non-blank line of one sign text, each trimmed, joined with single
 * spaces ({@code :557-563}).</li>
 * <li>{@link #ofSign(List, List)} is {@code getSign}: the <b>front</b> text wins whenever it yields anything at all,
 * because Create walks {@code Iterate.trueAndFalse} ({@code :556}).</li>
 * <li>{@link #lastOf(List)} is the outermost one: six neighbours in {@code Direction.values()} order, and the
 * <b>last</b> non-blank address wins rather than the first ({@code :539-545}).</li>
 * </ol>
 * <b>{@code trim()}, not {@code strip()}.</b> Create trims each line with {@link String#trim()}, which only removes
 * characters up to {@code U+0020}, while "is this line blank at all" is {@link String#isBlank()}, which is
 * Unicode-aware. A line of no-break spaces is therefore <b>not</b> blank and is <b>not</b> trimmed away, and a box
 * really does get that address. Mirroring means mirroring that too: this class must be byte-exact, not tidy
 * ({@code AisleName} is the opposite case — a name a player gives us, which we may sanitise).
 * <p>
 * <b>What this does not model.</b> An attached ComputerCraft computer can override the sign address entirely
 * ({@code PackagerBlockEntity.java:546-549}); this reads signs only, so a computer-addressed Packager is reported as
 * whatever its signs say. {@link #shorten(String)} is the one place where display and truth part company on purpose.
 * <p>
 * Pure Java, no Minecraft: {@code core.*} is JUnit-testable (ADR-038), which is the whole reason the rule lives here
 * and not in the block entity that draws it ({@code PackagerSignAddressTest}).
 */
public final class PackagerSignAddress {
    /** No address at all — an unaddressed package. Never {@code null}, so a caller can compare and format freely. */
    public static final String NONE = "";

    /**
     * Characters of an address a surface draws before it is cut off ({@link #shorten(String)}).
     * <p>
     * 25 is <b>Create's own</b> number: {@code PackagePortScreen.java:65} caps its address box at
     * {@code setMaxLength(25)}, so it is the length Create itself considers an address. It is a cap on the
     * <b>display</b> only and never on the address: a sign can carry four lines of up to 90 px each on both of its
     * faces, and all eight of them joined would be drawn past the edge of the screen, because a goggle line is never
     * wrapped (the measurement is in {@code CombVisualScenario#checkAisleListWidth}). The same number bounds the door
     * strip of M27, so there is one rule for "how much of an address fits a row".
     */
    public static final int DISPLAY_LENGTH = 25;

    private PackagerSignAddress() {
    }

    /**
     * The address one sign text spells: every non-blank line, each trimmed, joined with single spaces.
     * <p>
     * Mirrors the inner loop of {@code PackagerBlockEntity#getSign} ({@code :557-563}) including its
     * {@link String#trim()} and its {@link String#isBlank()} (see the class javadoc). Total: {@code null}, an empty
     * list and a list of blank lines all give {@link #NONE}.
     *
     * @param lines the sign's four messages, unfiltered, in order; {@code null} entries are ignored
     * @return the address, or {@link #NONE} — never {@code null}
     */
    public static String of(@Nullable List<String> lines) {
        if (lines == null || lines.isEmpty())
            return NONE;
        StringBuilder address = new StringBuilder();
        for (String line : lines) {
            if (line == null || line.isBlank())
                continue;
            address.append(line.trim()).append(' ');
        }
        // Create tests the joined text for blankness before trimming it, which matters for the no-break-space case:
        // a line of them is not blank, survives the trim, and is a real address.
        String joined = address.toString();
        return joined.isBlank() ? NONE : joined.trim();
    }

    /**
     * The address of a whole sign: the <b>front</b> text if it spells anything, otherwise the back.
     * <p>
     * Mirrors {@code PackagerBlockEntity#getSign} ({@code :553-568}), whose {@code Iterate.trueAndFalse} is
     * {@code {true, false}} ({@code Iterate.java:13}) — so a blank front face is the only way the back is ever read.
     *
     * @return the address, or {@link #NONE} if neither face spells one
     */
    public static String ofSign(@Nullable List<String> frontLines, @Nullable List<String> backLines) {
        String front = of(frontLines);
        return front.isEmpty() ? of(backLines) : front;
    }

    /**
     * The address a Packager ends up with, given what each of its six neighbours says: the <b>last</b> non-blank one.
     * <p>
     * Mirrors {@code PackagerBlockEntity#updateSignAddress} ({@code :539-545}), which assigns rather than breaks, so
     * with signs on two faces the one later in {@code Direction.values()} order wins — {@code DOWN, UP, NORTH, SOUTH,
     * WEST, EAST} ({@code Iterate.directions} is {@code Direction.values()}, {@code Iterate.java:17}). First would be
     * the natural guess and would be wrong, which is why this is its own named function with its own test.
     *
     * @param signAddresses one entry per neighbour, in {@code Direction.values()} order; blanks and {@code null}s mean
     *                      "no sign there"
     * @return the winning address, or {@link #NONE} if no neighbour spells one
     */
    public static String lastOf(@Nullable List<String> signAddresses) {
        if (signAddresses == null)
            return NONE;
        String address = NONE;
        for (String candidate : signAddresses) {
            if (candidate == null || candidate.isBlank())
                continue;
            address = candidate;
        }
        return address;
    }

    /**
     * {@code address} cut to {@value #DISPLAY_LENGTH} characters for a row that cannot wrap, never through a surrogate
     * pair and never ending in whitespace the cut exposed.
     * <p>
     * A caller that draws this must mark the cut ({@link #wouldShorten(String)}, and {@code WareworksLang} appends the
     * ellipsis), because a silently shortened address would read as the whole one and a player would compare it against
     * a Package Port filter that cannot match it.
     *
     * @return the text to draw — never {@code null}, and {@code address} itself when it already fits
     */
    public static String shorten(@Nullable String address) {
        if (address == null)
            return NONE;
        if (address.length() <= DISPLAY_LENGTH)
            return address;
        int end = DISPLAY_LENGTH;
        // A cut between the halves of a surrogate pair leaves half a code point, which no font can draw.
        if (Character.isHighSurrogate(address.charAt(end - 1)))
            end--;
        while (end > 0 && isBlank(address.charAt(end - 1)))
            end--;
        return address.substring(0, end);
    }

    /** Whether {@link #shorten(String)} would lose characters off the end of {@code address}. */
    public static boolean wouldShorten(@Nullable String address) {
        return address != null && address.length() > DISPLAY_LENGTH;
    }

    /**
     * Whether this character would draw as a ragged edge at the end of a cut address: whitespace in the game's wider
     * sense, {@code isWhitespace || isSpaceChar}, which also covers the no-break space {@link String#strip()} keeps.
     * Mirrors {@code StringUtil.isWhitespace}, for the reason {@code AisleName} spells out — no Minecraft here.
     */
    private static boolean isBlank(char character) {
        return Character.isWhitespace(character) || Character.isSpaceChar(character);
    }
}
