package dev.wareworks.content.station;

import java.util.Locale;
import java.util.Optional;

/**
 * What a player's action on a warehouse terminal's crafting panel became ({@code docs/warehouse-system.md} §3.4.5,
 * M32, issue #24): the one line the screen shows for it.
 * <p>
 * <b>Two kinds of action share it.</b> The first seven values answer a <b>craft</b> click on the result cell (slice 1):
 * only {@link #CRAFTED} means that every craft the click asked for was made, and every other one of them is the reason
 * the crafting <b>stopped</b>. A Shift-click can stop after it already crafted a few times: the answer then carries the
 * refusal together with how many items were made before it ({@link TerminalCraftOutcome}), so "Crafted Chest x5; no
 * room for more" is one answer rather than two. A refusal of the very first craft moved nothing at all — no item, no
 * statistic, no event ({@code TerminalCrafting}).
 * <p>
 * The rest answer the panel's <b>Fetch</b> button and the craft order it starts (slice 2, ADR-062): the second list
 * order a terminal can run beside its clipboard's, which brings the grid's ingredients into the buffer by ordinary
 * crane trips. {@link #isFetch()} tells the two kinds apart. {@link #ASKING} and {@link #CHANGED} mean that <b>nothing
 * was requested</b> — the player is shown what the fetch would really bring and decides — and the three refusals of a
 * Fetch ({@link #NOTHING_TO_FETCH}, {@link #NOT_ENOUGH}, {@link #NO_ROOM_FOR_ONE}) requested nothing either. A Fetch is
 * checked against the grid and its recipe exactly as a craft is, so it can also end in {@link #GRID_EMPTY},
 * {@link #NO_RECIPE}, {@link #LOCKED} or {@link #OUT_OF_REACH}.
 * <p>
 * {@link #LAID} answers a recipe a recipe viewer laid into the grid (slice 4): the server picked one item per cell and
 * the grid matches a crafting recipe. Laying moves nothing and orders nothing, so it counts as a craft's answer. Its
 * own refusal is {@link #NOT_LAID} (the picked grid matches no recipe), which speaks of the viewer's recipe and not of
 * the grid on screen, because the screen still shows the grid the lay would have replaced; the others are a craft's
 * and a Fetch's: {@link #OUT_OF_REACH}, {@link #ALREADY_RUNNING} (a craft order locks the grid) and
 * {@link #GRID_EMPTY} (no cell named an item).
 * <p>
 * {@link #name()} is a wire name only inside one version's payload, which is why the payload carries it by name and an
 * unknown one reads as a plain refusal; {@link #langKey()} is the sentence.
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
    GRID_EMPTY,

    // --- Fetch (slice 2 of issue #24, ADR-062) ---------------------------------------------------------------------

    /** The craft order is running: the crane brings what the buffer is missing for the crafts the outcome names. */
    STARTED,
    /**
     * Fewer crafts than asked for can be fetched, or an ingredient would have to be made first by a production chain
     * (slice 3): the player is asked once, with the numbers a Yes will start ({@code CraftFetchQuestion}). Nothing was
     * requested and nothing ordered. Also the answer to the order button's <b>Answer</b> while a portion's question is
     * waiting: that question is then put up again and nothing changes.
     */
    ASKING,
    /**
     * A Yes arrived, but the warehouse changed since the question was asked: the fetch it agreed to is no longer
     * possible as it was shown. Nothing was requested; the new question comes with the answer.
     */
    CHANGED,
    /** The buffer already holds every craft asked for. Nothing was requested: the player can craft right away. */
    NOTHING_TO_FETCH,
    /**
     * Not one craft more than the buffer holds can be supplied; the outcome names the item that falls short — or, when
     * a production chain was tried for it and does not hold, the item that chain could not get past, with the plan
     * refusal ("Oak Log is missing", slice 3).
     */
    NOT_ENOUGH,
    /** The buffer has no room for one more craft's ingredients once the open requests to it have arrived. */
    NO_ROOM_FOR_ONE,
    /** A craft order is already open at this terminal; it has to be finished or cancelled first. */
    ALREADY_RUNNING,
    /** The terminal is misaligned, outside a warehouse, or its controller is not loaded. */
    NO_AISLE,
    /** A question one portion of the craft order raised was answered, and the order runs again. */
    ANSWERED,
    /**
     * The player said <b>no</b> to the question one portion raised: nothing was accepted and the order parks, so the
     * order button becomes <b>Resume</b> and the same question can be had again.
     */
    DECLINED,
    /** A parked craft order was given another try. */
    RESUMED,
    /** The craft order was given up. Only its own share of its requests is given back; delivered items stay. */
    CANCELLED,
    /** No craft order is open here, so there is nothing to answer, resume or cancel. */
    NOT_RUNNING,

    // --- a recipe laid from a recipe viewer (slice 4 of issue #24) -------------------------------------------------

    /**
     * A recipe viewer's recipe was laid into the grid: the outcome names its result. Nothing moved and nothing was
     * requested; the grid itself reaches the screen with the next crafting view.
     */
    LAID,
    /**
     * A recipe viewer's recipe was not laid: the grid the server picked from its candidates matches no crafting recipe
     * of this server (a recipe the viewer draws with items of its own, or one only the client knows). Nothing changed;
     * the grid on screen stays what it was.
     */
    NOT_LAID;

    private static final String LANG_PREFIX = "gui.terminal.craft.result.";

    /**
     * Whether the action did what the player asked for: every craft of a click was made, or a Fetch, an answer, a
     * no, a resume or a cancel was carried out. {@link #NOTHING_TO_FETCH} counts too: the ingredients the player asked
     * for are already here, which is the good news the screen shows in green.
     */
    public boolean isSuccess() {
        return switch (this) {
            case CRAFTED, STARTED, NOTHING_TO_FETCH, ANSWERED, DECLINED, RESUMED, CANCELLED, LAID -> true;
            default -> false;
        };
    }

    /**
     * Whether only the Fetch button or the craft order can end in this. Four refusals answer both kinds of action with
     * the same sentence — {@link #NO_RECIPE}, {@link #LOCKED}, {@link #OUT_OF_REACH} and {@link #GRID_EMPTY}, because a
     * Fetch is checked against the same grid and recipe a craft is — and count as a craft's here, and so do
     * {@link #LAID} and {@link #NOT_LAID}, which neither fetch nor order anything.
     */
    public boolean isFetch() {
        return switch (this) {
            case CRAFTED, NO_RECIPE, NOT_IN_BUFFER, NO_ROOM, LOCKED, OUT_OF_REACH, GRID_EMPTY, LAID, NOT_LAID -> false;
            case STARTED, ASKING, CHANGED, NOTHING_TO_FETCH, NOT_ENOUGH, NO_ROOM_FOR_ONE, ALREADY_RUNNING, NO_AISLE,
                    ANSWERED, DECLINED, RESUMED, CANCELLED, NOT_RUNNING -> true;
        };
    }

    /**
     * Whether nothing was requested because the player is being asked first: a fresh question about the fetch, or the
     * same question again after the warehouse changed.
     */
    public boolean isAsking() {
        return this == ASKING || this == CHANGED;
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
