package dev.wareworks.core.address;

import java.util.Optional;

/**
 * Rack side of a storage position, relative to the aisle direction (the facing of the stacker crane dock).
 * <p>
 * Looking along the aisle, {@link #LEFT} is the counter-clockwise side and {@link #RIGHT} the clockwise side
 * ({@code docs/warehouse-system.md} §1). The letter is the suffix of a {@link StorageAddress}.
 */
public enum Side {
    LEFT('L'),
    RIGHT('R');

    private final char letter;

    Side(char letter) {
        this.letter = letter;
    }

    /** The address suffix: {@code 'L'} or {@code 'R'}. */
    public char letter() {
        return letter;
    }

    public Side opposite() {
        return this == LEFT ? RIGHT : LEFT;
    }

    /**
     * Lateral offset of this rack side from the aisle line, measured towards the clockwise direction:
     * {@code -1} for {@link #LEFT}, {@code +1} for {@link #RIGHT}.
     */
    public int lateralOffset() {
        return this == RIGHT ? 1 : -1;
    }

    /** The side with the given address letter (case-sensitive), or empty. */
    public static Optional<Side> fromLetter(char letter) {
        for (Side side : values()) {
            if (side.letter == letter)
                return Optional.of(side);
        }
        return Optional.empty();
    }

    /**
     * The side at a lateral offset from the aisle line measured towards the clockwise direction ({@code -1} is
     * {@link #LEFT}, {@code +1} is {@link #RIGHT}); empty for any other offset, including the aisle line itself.
     */
    public static Optional<Side> fromLateralOffset(int offset) {
        return switch (offset) {
            case -1 -> Optional.of(LEFT);
            case 1 -> Optional.of(RIGHT);
            default -> Optional.empty();
        };
    }
}
