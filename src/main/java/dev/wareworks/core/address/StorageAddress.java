package dev.wareworks.core.address;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Human-readable address of a rack position: {@code A-03-07R} is aisle {@code A}, level 3, position 7, rack side
 * {@code R} ({@code docs/warehouse-system.md} §2, ADR-009).
 * <p>
 * Level and position are written with at least two digits ({@code %02d}), so levels and positions up to 99 give the
 * familiar {@code A-LL-PP} form and three-digit values stay readable ({@code B-100-128L}). {@link #parse(String)} is
 * strict: it accepts only the canonical form that {@link #format()} produces (upper-case aisle letter, no extra leading
 * zeros, no whitespace), so every address has exactly one spelling.
 * <p>
 * Addresses are derived from aisle-local {@link RackPosition}s and never stored as the source of truth.
 *
 * @param aisle    aisle letter {@code 'A'..'Z'}
 * @param level    level number, {@code y + 1} ({@value #MIN_LEVEL}..{@value #MAX_LEVEL})
 * @param position aisle position {@code x} ({@value #MIN_POSITION}..{@value #MAX_POSITION}); 0 is the dock column
 * @param side     rack side
 */
public record StorageAddress(char aisle, int level, int position, Side side) {
    public static final char FIRST_AISLE = 'A';
    public static final char LAST_AISLE = 'Z';
    /** Number of distinct aisle letters. */
    public static final int AISLE_COUNT = LAST_AISLE - FIRST_AISLE + 1;
    public static final int MIN_LEVEL = 1;
    /** Largest level with a three-digit address. */
    public static final int MAX_LEVEL = 999;
    public static final int MIN_POSITION = 0;
    /** Largest position with a three-digit address. */
    public static final int MAX_POSITION = 999;

    private static final String SEPARATOR = "-";
    private static final String NUMBER_FORMAT = "%02d";
    private static final Pattern CANDIDATE = Pattern.compile("([A-Z])-([0-9]{2,3})-([0-9]{2,3})([LR])");

    public StorageAddress {
        if (!isValidAisle(aisle))
            throw new IllegalArgumentException("aisle must be a letter A-Z: '" + aisle + "'");
        if (level < MIN_LEVEL || level > MAX_LEVEL)
            throw new IllegalArgumentException("level must be in " + MIN_LEVEL + ".." + MAX_LEVEL + ": " + level);
        if (position < MIN_POSITION || position > MAX_POSITION)
            throw new IllegalArgumentException(
                    "position must be in " + MIN_POSITION + ".." + MAX_POSITION + ": " + position);
        Objects.requireNonNull(side, "side");
    }

    /** The address of an aisle-local rack position in the given aisle: level {@code y + 1}, position {@code x}. */
    public static StorageAddress of(char aisle, RackPosition rack) {
        Objects.requireNonNull(rack, "rack");
        if (rack.y() >= MAX_LEVEL)
            throw new IllegalArgumentException("level index too large for an address: " + rack.y());
        return new StorageAddress(aisle, rack.y() + 1, rack.x(), rack.side());
    }

    /** The aisle-local rack position of this address. */
    public RackPosition rackPosition() {
        return new RackPosition(position, level - 1, side);
    }

    /** Zero-based index of the aisle letter ({@code 'A'} is 0). */
    public int aisleIndex() {
        return aisle - FIRST_AISLE;
    }

    public static boolean isValidAisle(char letter) {
        return letter >= FIRST_AISLE && letter <= LAST_AISLE;
    }

    /** The aisle letter for a zero-based index ({@code 0} is {@code 'A'}, {@code 25} is {@code 'Z'}). */
    public static char aisleLetter(int index) {
        if (index < 0 || index >= AISLE_COUNT)
            throw new IllegalArgumentException("aisle index must be in 0.." + (AISLE_COUNT - 1) + ": " + index);
        return (char) (FIRST_AISLE + index);
    }

    /** The canonical text form, e.g. {@code A-03-07R}. */
    public String format() {
        return aisle + SEPARATOR + String.format(Locale.ROOT, NUMBER_FORMAT, level) + SEPARATOR
                + String.format(Locale.ROOT, NUMBER_FORMAT, position) + side.letter();
    }

    /**
     * Parses the canonical form produced by {@link #format()}.
     *
     * @throws IllegalArgumentException if the text is not a canonical, in-range address
     * @throws NullPointerException     if the text is {@code null}
     */
    public static StorageAddress parse(String text) {
        Objects.requireNonNull(text, "text");
        Matcher matcher = CANDIDATE.matcher(text);
        if (!matcher.matches())
            throw new IllegalArgumentException("not a storage address (expected e.g. A-03-07R): \"" + text + "\"");
        StorageAddress address = new StorageAddress(matcher.group(1).charAt(0), Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)), Side.fromLetter(matcher.group(4).charAt(0)).orElseThrow());
        if (!address.format().equals(text))
            throw new IllegalArgumentException("not the canonical form " + address.format() + ": \"" + text + "\"");
        return address;
    }

    /** Like {@link #parse(String)}, but returns empty for {@code null} or invalid text instead of throwing. */
    public static Optional<StorageAddress> tryParse(String text) {
        if (text == null)
            return Optional.empty();
        try {
            return Optional.of(parse(text));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return format();
    }
}
