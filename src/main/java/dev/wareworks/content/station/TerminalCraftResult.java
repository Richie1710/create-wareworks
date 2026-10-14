package dev.wareworks.content.station;

import java.util.Locale;
import java.util.Optional;

/**
 * What a player's craft at a warehouse terminal became ({@code docs/warehouse-system.md} §3.4.5, M32, issue #24): the
 * one line the screen shows for it.
 * <p>
 * Only {@link #CRAFTED} means that every craft the click asked for was made. Every other value is the reason the
 * crafting <b>stopped</b>, and a Shift-click can stop after it already crafted a few times: the answer then carries the
 * refusal together with how many items were made before it ({@link TerminalCraftOutcome}), so "Crafted Chest x5; no
 * room for more" is one answer rather than two. A refusal of the very first craft moved nothing at all — no item, no
 * statistic, no event ({@code TerminalCrafting}).
 * <p>
 * The values of the craft fetch (slice 2 of issue #24) are added to this enum when it comes. {@link #name()} is a wire
 * name only inside one version's payload, which is why the payload carries it by name and an unknown one reads as a
 * plain refusal; {@link #langKey()} is the sentence.
 */
public enum TerminalCraftResult {
    /** Every craft the click asked for was made; the outcome names the result and how many items it came to. */
    CRAFTED,
    /** The grid matches no crafting recipe, or the recipe it matches makes nothing in this world. */
    NO_RECIPE,
    /** The buffer holds fewer of an ingredient than one craft takes; the outcome names that ingredient. */
    NOT_IN_BUFFER,
    /**
     * A remainder or the result fits neither the buffer nor the player's inventory; the outcome names that item. The
     * craft was refused before anything moved, so nothing was dropped.
     */
    NO_ROOM,
    /** Limited crafting is on and the player has not unlocked this recipe. */
    LOCKED,
    /** The player may not use this terminal any more (too far away, the block is gone, or another screen is open). */
    OUT_OF_REACH,
    /** No cell of the grid names an item, so there is nothing to craft. */
    GRID_EMPTY;

    private static final String LANG_PREFIX = "gui.terminal.craft.result.";

    /** Whether the click did everything it asked for. */
    public boolean isSuccess() {
        return this == CRAFTED;
    }

    /** Relative lang key of this result's sentence, e.g. {@code gui.terminal.craft.result.no_room}. */
    public String langKey() {
        return LANG_PREFIX + name().toLowerCase(Locale.ROOT);
    }

    /** The result with the given name, or empty for {@code null} and unknown names. */
    public static Optional<TerminalCraftResult> byName(String name) {
        if (name == null)
            return Optional.empty();
        for (TerminalCraftResult result : values()) {
            if (result.name().equals(name))
                return Optional.of(result);
        }
        return Optional.empty();
    }
}
