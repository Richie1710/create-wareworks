package dev.wareworks.content.station;

import java.util.Locale;
import java.util.Optional;

/**
 * What a player's action on a warehouse terminal's clipboard order became ({@code docs/warehouse-system.md} §3.4.4,
 * M23, issue #19): the one line the screen shows for it.
 * <p>
 * The three that are not refusals are separate on purpose. {@link #ASKING} means <b>nothing was started</b> — the
 * player is shown what the list would really cost and decides — while {@link #STARTED} means the order is running and
 * {@link #ANSWERED} that a question a portion raised was settled. {@link #name()} is a wire name only inside one
 * version's payload; {@link #langKey()} is the sentence.
 */
public enum TerminalListResult {
    /** The order is running: the crane works the list off in portions from now on. */
    STARTED,
    /** The list falls short somewhere, or something would have to be produced: the player is asked first. */
    ASKING,
    /** A question a portion raised was answered and the order runs again. */
    ANSWERED,
    /**
     * The question a portion raised is on screen again, and nothing changed (M23 review fix).
     * <p>
     * It is what the list button's <b>Answer</b> really does: the question lives on the server, so a player who closed
     * the panel asks for it to be sent again rather than being left with an order nobody can answer.
     */
    QUESTION,
    /**
     * The player said <b>no</b> to the question a portion raised: nothing was accepted and the order parks, so the
     * list button becomes <b>Resume</b> and the same question can be had again.
     */
    DECLINED,
    /** A parked order was given another try. */
    RESUMED,
    /** The order was given up. Its open requests are cancelled; delivered items stay where they are. */
    CANCELLED,
    /** The list slot holds no clipboard. */
    NO_CLIPBOARD,
    /** The clipboard carries nothing to fetch: no entry, every entry already ticked, or every amount zero. */
    EMPTY_LIST,
    /** An order is already running on this terminal; it has to be cancelled or finished first. */
    ALREADY_RUNNING,
    /** Nothing is running, so there is nothing to answer, resume or cancel. */
    NOT_RUNNING,
    /** The terminal is misaligned, outside a warehouse, or its controller is not loaded. */
    NO_AISLE,
    /** The player may not use this terminal (too far away, or the block is gone). */
    OUT_OF_REACH;

    private static final String LANG_PREFIX = "gui.terminal.list.result.";

    /** Whether the action did what the player asked for. */
    public boolean isSuccess() {
        return this == STARTED || this == ANSWERED || this == RESUMED || this == CANCELLED || this == QUESTION
                || this == DECLINED;
    }

    /** Whether nothing happened because the player is being asked first. */
    public boolean isAsking() {
        return this == ASKING;
    }

    /** Relative lang key of this result's sentence, e.g. {@code gui.terminal.list.result.empty_list}. */
    public String langKey() {
        return LANG_PREFIX + name().toLowerCase(Locale.ROOT);
    }

    /** The result with the given name, or empty for {@code null} and unknown names. */
    public static Optional<TerminalListResult> byName(String name) {
        if (name == null)
            return Optional.empty();
        for (TerminalListResult result : values()) {
            if (result.name().equals(name))
                return Optional.of(result);
        }
        return Optional.empty();
    }
}
