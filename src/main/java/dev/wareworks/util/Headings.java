package dev.wareworks.util;

import dev.wareworks.core.address.Heading;
import net.minecraft.core.Direction;

/**
 * Converts between Minecraft's horizontal {@link Direction} and {@code core}'s {@link Heading}, which names the same
 * four directions without a Minecraft type (ADR-033).
 * <p>
 * The conversion is by name, not by ordinal, so neither side can drift into the other silently.
 */
public final class Headings {
    private Headings() {
    }

    /** The {@link Heading} of a horizontal {@link Direction}. */
    public static Heading of(Direction direction) {
        return switch (direction) {
            case NORTH -> Heading.NORTH;
            case EAST -> Heading.EAST;
            case SOUTH -> Heading.SOUTH;
            case WEST -> Heading.WEST;
            default -> throw new IllegalArgumentException("not a horizontal direction: " + direction);
        };
    }

    /** The {@link Direction} of a {@link Heading}. */
    public static Direction direction(Heading heading) {
        return switch (heading) {
            case NORTH -> Direction.NORTH;
            case EAST -> Direction.EAST;
            case SOUTH -> Direction.SOUTH;
            case WEST -> Direction.WEST;
        };
    }
}
