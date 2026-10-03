package dev.wareworks.core.warehouse;

import org.jetbrains.annotations.Nullable;

/**
 * The one spelling rule for the name a player gives an aisle (M25, issue #15): what a name may contain, how long it may
 * be, and what "no name" looks like.
 * <p>
 * <b>Why a sanitiser and not a validator.</b> A name arrives from four places that cannot be trusted to agree — the
 * custom name of a held item, a saved tag, a block entity update tag, and a hand-edited world — and three of them can
 * hold text no entry rule ever saw. A validator would have to decide what to do when one of them says no, which is a
 * decision at the wrong end: by then the name is already in the save. {@link #sanitize(String)} is therefore
 * <b>total</b> — every {@code String} and {@code null} map to a legal name — and <b>idempotent</b>, so the save, the
 * packet and every surface that draws the name necessarily agree on it. Nothing here throws.
 * <p>
 * <b>The rules, in order.</b>
 * <ol>
 * <li>Characters the game cannot draw are dropped: the section sign {@code §} (167), everything below the space, and
 * {@code DEL} (127). That is exactly the set {@code net.minecraft.util.StringUtil.isAllowedChatCharacter} rejects
 * ({@code build/api-src/neoforge/net/minecraft/util/StringUtil.java:64-66}), so a name is filtered the same way
 * vanilla filters a sign, a book and a chat line. It is <b>re-implemented</b> here rather than called, because
 * {@code core.*} is pure Java with no Minecraft on its JUnit classpath ({@code docs/architecture.md}, ADR-038; the
 * same reason {@code NetworkGoggleInfoTest} leaves NBT to a GameTest) — a {@code StringUtil} call would compile and
 * then throw {@code NoClassDefFoundError} in every test of this class.</li>
 * <li>Whitespace is trimmed off both ends and internal runs collapse to one space. Whitespace is the game's own
 * notion, {@code Character.isWhitespace(c) || Character.isSpaceChar(c)} ({@code StringUtil.isWhitespace:90-92}), which
 * is wider than {@link String#strip()}: a no-break space (160) is drawn as nothing but survives both {@code filterText}
 * and {@code strip}, so without this a hand-edited save could hold a {@value #MAX_LENGTH}-character name that renders
 * as empty space and still reports itself as present.</li>
 * <li>What is left is cut to {@value #MAX_LENGTH} characters, never through a surrogate pair, and any whitespace the
 * cut exposed at the end is trimmed again.</li>
 * </ol>
 * <p>
 * <b>Why {@value #MAX_LENGTH}.</b> Create's sign display target is 15 columns wide, so 16 is the honest "this will be
 * cut on a sign" number; the controller's goggle line lists six aisles, so six names plus the dock aisle's stay well
 * inside the 2048-byte sync budgets the mod holds itself to. Vanilla's anvil allows 50
 * ({@code AnvilMenu.MAX_NAME_LENGTH}), so this truncates what a player is allowed to type rather than inventing an
 * entry rule the anvil would have to enforce — and {@link #wouldCut(String)} exists so the player is told.
 */
public final class AisleName {
    /** The longest name that is kept; anything beyond it is cut off ({@link #wouldCut(String)}). */
    public static final int MAX_LENGTH = 16;

    /** The name of an aisle nobody has named. {@link #sanitize(String)} maps every unusable input to it. */
    public static final String NONE = "";

    private AisleName() {
    }

    /**
     * The given text as a name may be stored, synced and drawn, following the three rules in the class javadoc.
     * <p>
     * Total and idempotent: {@code sanitize(sanitize(s)).equals(sanitize(s))} for every {@code s}, and
     * {@code sanitize(null)} is {@link #NONE}. A text that is already a legal name is returned unchanged, without
     * copying it, so re-sanitising on every read costs one scan of at most {@value #MAX_LENGTH} characters.
     *
     * @return the name, or {@link #NONE} ({@code ""}) for "no name" — never {@code null}
     */
    public static String sanitize(@Nullable String raw) {
        if (raw == null || raw.isEmpty())
            return NONE;
        if (isSanitized(raw))
            return raw;

        StringBuilder name = new StringBuilder(Math.min(raw.length(), MAX_LENGTH));
        boolean pendingSpace = false;
        for (int index = 0; index < raw.length(); index++) {
            char character = raw.charAt(index);
            if (!isDrawable(character))
                continue;
            if (isBlank(character)) {
                // A run of any width of whitespace becomes one plain space, and only once something follows it: that
                // drops the leading run entirely and leaves the trailing one pending for ever.
                pendingSpace = !name.isEmpty();
                continue;
            }
            if (pendingSpace) {
                if (name.length() + 1 >= MAX_LENGTH)
                    break; // The separator would be the last character kept, so the next word cannot follow it anyway.
                name.append(' ');
                pendingSpace = false;
            }
            if (name.length() >= MAX_LENGTH)
                break;
            name.append(character);
        }
        // The loop stops at MAX_LENGTH, which can leave a high surrogate last: its pair is gone, so drop it rather
        // than store half a code point no surface can draw. Dropping it can expose the space before it, or - in
        // malformed input - a second high surrogate, so this trims until the end is something that may be last.
        while (!name.isEmpty() && isUnfitAtEnd(name.charAt(name.length() - 1)))
            name.setLength(name.length() - 1);
        // NONE and not an empty builder: "no name" has exactly one representation, so a caller may compare against
        // the constant by identity and re-sanitising is identity-stable for the blank case too.
        return name.isEmpty() ? NONE : name.toString();
    }

    /**
     * Whether {@link #sanitize(String)} would lose characters off the end of {@code raw} because it is too long — the
     * one case worth echoing back to the player ({@code message.aisle_named_cut}).
     * <p>
     * Deliberately <b>only</b> the length: a dropped section sign or a collapsed double space changes text the player
     * could not see in the first place, and "shortened to ..." would then report a change they cannot perceive. Being
     * told that a name they can read in their hand is too long is the only surprise the gesture can hand them.
     */
    public static boolean wouldCut(@Nullable String raw) {
        if (raw == null)
            return false;
        return drawableLength(raw) > MAX_LENGTH;
    }

    /** Whether {@code raw} is already exactly what {@link #sanitize(String)} would return, so no copy is needed. */
    private static boolean isSanitized(String raw) {
        int length = raw.length();
        if (length > MAX_LENGTH)
            return false;
        for (int index = 0; index < length; index++) {
            char character = raw.charAt(index);
            if (!isDrawable(character))
                return false;
            if (!isBlank(character))
                continue;
            // Legal whitespace is a single plain space with a kept character on either side of it.
            if (character != ' ' || index == 0 || index == length - 1 || isBlank(raw.charAt(index - 1)))
                return false;
        }
        return length == 0 || !isUnfitAtEnd(raw.charAt(length - 1));
    }

    /**
     * Whether this character may not be the last one of a name: whitespace, which would draw as a ragged edge, and a
     * high surrogate, which without its pair is half a code point.
     */
    private static boolean isUnfitAtEnd(char character) {
        return isBlank(character) || Character.isHighSurrogate(character);
    }

    /**
     * How long {@code raw} would be after the first two rules but <b>before</b> the cut — the length
     * {@link #wouldCut(String)} compares, counted without building the string.
     */
    private static int drawableLength(String raw) {
        int length = 0;
        boolean pendingSpace = false;
        for (int index = 0; index < raw.length(); index++) {
            char character = raw.charAt(index);
            if (!isDrawable(character))
                continue;
            if (isBlank(character)) {
                pendingSpace = length > 0;
                continue;
            }
            if (pendingSpace) {
                length++;
                pendingSpace = false;
            }
            length++;
        }
        return length;
    }

    /**
     * Whether the game can draw this character: everything but the section sign, the controls below the space and
     * {@code DEL}. Mirrors {@code StringUtil.isAllowedChatCharacter} (see the class javadoc for why it is mirrored).
     */
    private static boolean isDrawable(char character) {
        return character != 167 && character >= ' ' && character != 127;
    }

    /**
     * Whether this character is whitespace in the game's wider sense, {@code isWhitespace || isSpaceChar}, which also
     * covers the no-break space a {@link String#strip()} would keep. Mirrors {@code StringUtil.isWhitespace}.
     */
    private static boolean isBlank(char character) {
        return Character.isWhitespace(character) || Character.isSpaceChar(character);
    }
}
