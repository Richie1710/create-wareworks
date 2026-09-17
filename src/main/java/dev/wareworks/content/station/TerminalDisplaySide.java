package dev.wareworks.content.station;

import java.util.Optional;

import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;

/**
 * Where the warehouse terminal's screen sits, <b>relative to its intake port</b>
 * ({@code docs/warehouse-system.md} §3.4, ADR-022).
 * <p>
 * The terminal needs two independent horizontal directions: the port the crane reaches into (the aisle side, the block
 * state's {@code FACING}) and the face a player reads and empties (the screen). Storing the screen as a second
 * <i>absolute</i> direction would allow the state {@code screen == port}, which no model can draw. Storing it relative
 * to the port makes that state unrepresentable: there are exactly three values, and every one of them resolves to a
 * face that is not the port.
 * <p>
 * The mapping, for an intake port pointing {@code intake} (i.e. the port is on that face of the block):
 * <ul>
 * <li>{@link #BACK} — the screen is on the opposite face, the default and the way a terminal is placed;</li>
 * <li>{@link #LEFT} — the screen is one quarter turn counter-clockwise from the port (seen from above);</li>
 * <li>{@link #RIGHT} — the screen is one quarter turn clockwise from the port.</li>
 * </ul>
 * A saved block state from before this property existed reads back as {@link #BACK}, i.e. the screen on the face
 * opposite the aisle — which is exactly where a player of the old terminal stood to take items out, so no migration is
 * needed (ADR-022, "world compatibility").
 */
public enum TerminalDisplaySide implements StringRepresentable {
    /** The screen faces away from the intake port. */
    BACK("back"),
    /** The screen is a quarter turn counter-clockwise from the intake port. */
    LEFT("left"),
    /** The screen is a quarter turn clockwise from the intake port. */
    RIGHT("right");

    private final String serializedName;

    TerminalDisplaySide(String serializedName) {
        this.serializedName = serializedName;
    }

    @Override
    public String getSerializedName() {
        return serializedName;
    }

    /**
     * The world direction of the screen for a terminal whose intake port points {@code intake}.
     *
     * @throws IllegalArgumentException if {@code intake} is not horizontal (the block state property only holds
     *                                  horizontal directions, so this cannot happen for a real block)
     */
    public Direction of(Direction intake) {
        if (!intake.getAxis().isHorizontal())
            throw new IllegalArgumentException("the intake port must be horizontal: " + intake);
        return switch (this) {
            case BACK -> intake.getOpposite();
            case LEFT -> intake.getCounterClockWise();
            case RIGHT -> intake.getClockWise();
        };
    }

    /**
     * The value that puts the screen on {@code screen} for an intake port pointing {@code intake}; empty when the two
     * directions are the same face (a terminal cannot show its screen through its port) or either is not horizontal.
     */
    public static Optional<TerminalDisplaySide> of(Direction intake, Direction screen) {
        if (!intake.getAxis().isHorizontal() || !screen.getAxis().isHorizontal())
            return Optional.empty();
        for (TerminalDisplaySide side : values()) {
            if (side.of(intake) == screen)
                return Optional.of(side);
        }
        return Optional.empty();
    }

    /**
     * The next value clockwise (seen from above), for the wrench: the screen turns by one quarter, and the quarter that
     * would put it on the intake port is skipped, so {@link #LEFT} → {@link #RIGHT} → {@link #BACK} → {@link #LEFT}.
     * The port itself never moves ({@code docs/warehouse-system.md} §3.4.3).
     */
    public TerminalDisplaySide clockwise() {
        return switch (this) {
            case LEFT -> RIGHT;
            case RIGHT -> BACK;
            case BACK -> LEFT;
        };
    }
}
