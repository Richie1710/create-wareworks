package dev.wareworks.core.port;

import java.util.Locale;
import java.util.Optional;

/**
 * When a warehouse port acts ({@code docs/warehouse-system.md} §3.2, M17, issue #12).
 * <p>
 * The three values are the three rows of the port's hold-to-edit board, so {@link #ordinal()} <b>is</b> the board row
 * and the declaration order is part of that UI contract. {@link #name()} is the stable save name; a save from before
 * M17 carries no mode at all and reads back as {@link #PULSE}, which is exactly what every warehouse output did
 * before.
 */
public enum PortRedstone {
    /** One action per rising edge: today's warehouse output, and what a pulse clock drives. */
    PULSE,
    /** Acts while the signal is high. */
    WHILE_POWERED,
    /** Acts while the signal is <b>low</b>, so an unwired port works out of the box and a lever switches it off. */
    UNLESS_POWERED;

    /** What a port with no saved mode has: one action per rising edge. */
    public static final PortRedstone DEFAULT = PULSE;

    private static final String LANG_PREFIX = "output.redstone.";

    /**
     * Whether this mode keeps acting instead of acting once per edge. A continuous mode is the only reason a controller
     * has to look at a port again without being told to, so this is what its cache marks.
     */
    public boolean isContinuous() {
        return this != PULSE;
    }

    /** Relative lang key of the mode text, e.g. {@code output.redstone.while_powered}. */
    public String langKey() {
        return LANG_PREFIX + name().toLowerCase(Locale.ROOT);
    }

    /**
     * The mode shown in board row {@code row}; {@link #DEFAULT} for a row outside the board, so a clipboard or a
     * tampered packet can never leave a port in no mode at all.
     */
    public static PortRedstone byRow(int row) {
        PortRedstone[] values = values();
        return row < 0 || row >= values.length ? DEFAULT : values[row];
    }

    /** The mode with the given save name, or empty for {@code null} or unknown names. */
    public static Optional<PortRedstone> byName(String name) {
        if (name == null)
            return Optional.empty();
        for (PortRedstone mode : values()) {
            if (mode.name().equals(name))
                return Optional.of(mode);
        }
        return Optional.empty();
    }
}
