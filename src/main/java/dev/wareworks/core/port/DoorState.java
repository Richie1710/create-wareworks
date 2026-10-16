package dev.wareworks.core.port;

import java.util.Locale;
import java.util.Optional;

/**
 * How a <b>package door</b> stands at the moment it was looked at (M27, issue #18, ADR-065): a requesting warehouse port
 * of the terminal's own warehouse with a plain Create Packager at its back ({@code docs/warehouse-system.md} §3.4.6).
 * <p>
 * Each state is a fact a player can act on, ordered by {@link DoorFacts#state()} so the most actionable cause wins. Two
 * of them make a door <b>unselectable</b> ({@link #selectable()}): a door that ignores redstone never sends what it is
 * handed, and a door that is not loaded cannot be checked at all. The others are warnings: the order waits, the crane
 * stays free.
 * <p>
 * It travels <b>by name</b> on the wire ({@code TerminalDoorsPayload}), so the declaration order is free; a name a build
 * does not know reads as {@link #NOT_LOADED} ({@link #byName}), the fail-safe answer — a row nobody can choose.
 * <p>
 * Pure Java, no Minecraft: {@code core.*} is JUnit-testable.
 */
public enum DoorState {
    /** Ready: the next box goes out with the sign's address. */
    READY,
    /** No sign at the Packager, so its boxes leave unaddressed. A warning: belt-only builds take them as they are. */
    NO_ADDRESS,
    /** Every slot of the port is taken, so nothing is emptying it. A warning: the order waits, the crane stays free. */
    PORT_FULL,
    /**
     * A box has waited at rest in the Packager for ten seconds without being taken ({@link BoxWait}), so the door may
     * stand still: a Packager that holds a box makes no other. Usually the funnel was forgotten or the belt stopped,
     * but a slow taker that works can wait that long too, so the row asks rather than claims. A warning: the order
     * waits, the crane stays free.
     */
    BOX_WAITING,
    /** The Packager carries a Stock Link, so it ignores redstone and the door never sends. Not selectable. */
    IGNORES_REDSTONE,
    /** The door's chunk is not loaded; the row is remembered from an earlier look. Not selectable. */
    NOT_LOADED;

    private static final String LANG_PREFIX = "gui.terminal.door.state.";
    private static final String DETAIL_PREFIX = "gui.terminal.door.detail.";

    /** Whether a player may choose a door in this state. */
    public boolean selectable() {
        return this != IGNORES_REDSTONE && this != NOT_LOADED;
    }

    /**
     * Relative lang key of the state's word in a door row, e.g. {@code gui.terminal.door.state.port_full}. Empty for
     * {@link #READY}, which names its rack address instead of a state word.
     */
    public Optional<String> langKey() {
        return this == READY ? Optional.empty() : Optional.of(LANG_PREFIX + name().toLowerCase(Locale.ROOT));
    }

    /**
     * Relative lang key of the one sentence that says what to do about this state, e.g.
     * {@code gui.terminal.door.detail.port_full}: the row only has room for a word, the chosen door's detail line for
     * the sentence. Empty for {@link #READY}, which needs none.
     */
    public Optional<String> detailKey() {
        return this == READY ? Optional.empty() : Optional.of(DETAIL_PREFIX + name().toLowerCase(Locale.ROOT));
    }

    /** The state with this name, or {@link #NOT_LOADED} for {@code null} and for a name this build does not know. */
    public static DoorState byName(String name) {
        if (name != null) {
            for (DoorState state : values()) {
                if (state.name().equals(name))
                    return state;
            }
        }
        return NOT_LOADED;
    }
}
