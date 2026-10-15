package dev.wareworks.content.station;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.crafting.CraftFetchQuestion;
import dev.wareworks.core.production.PlanRefusal;

/**
 * What one action on a warehouse terminal's crafting panel became ({@code docs/warehouse-system.md} §3.4.5, M32,
 * issue #24): a craft click, or a press of the Fetch button and the craft order behind it.
 * <p>
 * <b>One answer for a whole Shift-click.</b> {@link #result()} says how the click ended —
 * {@link TerminalCraftResult#CRAFTED} when it made every craft it asked for, otherwise the refusal it stopped at — and
 * {@link #crafted()} how many items it made before that. A refusal with {@code crafted() > 0} is therefore "crafted x5,
 * then stopped because of this", and a refusal with {@code crafted() == 0} moved nothing at all.
 * <p>
 * <b>For a Fetch</b> ({@link TerminalCraftResult#isFetch()}) {@link #crafted()} is a number of <b>crafts</b>, never of
 * items: the crafts the started order fetches for ({@link TerminalCraftResult#STARTED}), the crafts the buffer already
 * holds ({@link TerminalCraftResult#NOTHING_TO_FETCH}), and the crafts that can be had at all for a question or a
 * refusal ({@link CraftFetchQuestion#possible()}), which is the hint a player can set the amount field to.
 * <p>
 * {@link #about()} is the item the sentence names: the result for {@link TerminalCraftResult#CRAFTED},
 * {@link TerminalCraftResult#STARTED} and {@link TerminalCraftResult#NOTHING_TO_FETCH}, the missing ingredient for
 * {@link TerminalCraftResult#NOT_IN_BUFFER} and {@link TerminalCraftResult#NOT_ENOUGH}, the item without room for
 * {@link TerminalCraftResult#NO_ROOM}. The other answers name nothing.
 *
 * @param result   how the action ended
 * @param crafted  for a craft, how many items the click made, i.e. the result's count summed over every craft that
 *                 happened; for a Fetch, a number of crafts (see above)
 * @param about    the item the sentence names; see above
 * @param refusal  why a production chain could not make up for the item {@link #about()} names. The grid's chain offer
 *                 is a later step of issue #24, so nothing sets it yet; it travels so that the sentence can name it
 *                 then without another change to the answer's shape
 * @param question what a Fetch would really bring, measured on the server: present for
 *                 {@link TerminalCraftResult#ASKING} after a Fetch, {@link TerminalCraftResult#CHANGED} and the two
 *                 measured refusals, {@link TerminalCraftResult#NOT_ENOUGH} and
 *                 {@link TerminalCraftResult#NO_ROOM_FOR_ONE}; empty for everything else
 */
public record TerminalCraftOutcome(TerminalCraftResult result, int crafted, Optional<ItemKey> about,
                                   Optional<PlanRefusal> refusal, Optional<CraftFetchQuestion<ItemKey>> question) {
    public TerminalCraftOutcome {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(about, "about");
        Objects.requireNonNull(refusal, "refusal");
        Objects.requireNonNull(question, "question");
        crafted = Math.max(0, crafted);
    }

    /** The answer to a craft click, or a Fetch answer with nothing measured behind it. */
    public TerminalCraftOutcome(TerminalCraftResult result, int crafted, Optional<ItemKey> about) {
        this(result, crafted, about, Optional.empty(), Optional.empty());
    }

    /** An answer that crafted nothing and names nothing. */
    public static TerminalCraftOutcome of(TerminalCraftResult result) {
        return new TerminalCraftOutcome(result, 0, Optional.empty());
    }

    /**
     * A Fetch answered from a measurement: {@code result} with the crafts that can be had at all, the item that falls
     * short when the warehouse is what binds, and the measurement itself for the screen's question.
     */
    public static TerminalCraftOutcome measured(TerminalCraftResult result, CraftFetchQuestion<ItemKey> question) {
        Objects.requireNonNull(question, "question");
        return new TerminalCraftOutcome(result, question.possible(), question.about(), Optional.empty(),
                Optional.of(question));
    }

    /** Whether the action did what the player asked for ({@link TerminalCraftResult#isSuccess()}). */
    public boolean isSuccess() {
        return result.isSuccess();
    }
}
