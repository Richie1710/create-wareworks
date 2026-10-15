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
 * {@link TerminalCraftResult#NOT_IN_BUFFER}, the missing ingredient or the item the tried chain could not get past for
 * {@link TerminalCraftResult#NOT_ENOUGH}, the item without room for {@link TerminalCraftResult#NO_ROOM}. A measured
 * answer ({@link #measured}: {@link TerminalCraftResult#NOT_ENOUGH}, {@link TerminalCraftResult#NO_ROOM_FOR_ONE},
 * {@link TerminalCraftResult#ASKING} after a Fetch and {@link TerminalCraftResult#CHANGED}) carries the question's
 * {@link CraftFetchQuestion#reasonItem()} and chain refusal whenever the stock binds, and neither when the buffer's room
 * does; for ASKING and CHANGED the screen shows the question instead, where the refusal is a line of its own, and reads
 * neither. The other answers name nothing. When a production chain was tried for the ingredient a Fetch falls short of
 * and does not hold (issue #24, slice 3), {@link #refusal()} says why and {@link #about()} names the item that refusal
 * is about — "Oak Log is missing" rather than "not enough Oak Planks", because the logs are what the player has to do
 * something about ({@link CraftFetchQuestion#reasonItem()}).
 *
 * @param result   how the action ended
 * @param crafted  for a craft, how many items the click made, i.e. the result's count summed over every craft that
 *                 happened; for a Fetch, a number of crafts (see above)
 * @param about    the item the sentence names; see above
 * @param refusal  why a production chain could not make up for the ingredient the Fetch falls short of, when one was
 *                 tried for it ({@link CraftFetchQuestion#chainRefusal()}); {@link #about()} is then the item the
 *                 refusal names. Set only by {@link #measured} while the stock binds — NOT_ENOUGH turns it into the
 *                 status row's sentence, ASKING and CHANGED carry it inside their question. Empty for an ingredient
 *                 nothing in the aisle makes, when the buffer's room binds, and for every answer not built by
 *                 {@link #measured}
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
     * A Fetch answered from a measurement ({@link TerminalCraftResult#NOT_ENOUGH},
     * {@link TerminalCraftResult#NO_ROOM_FOR_ONE}, {@link TerminalCraftResult#ASKING},
     * {@link TerminalCraftResult#CHANGED}): {@code result} with the crafts that can be had at all, the item the reason
     * names when the warehouse is what binds — the ingredient that falls short, or, when a chain was tried for it and
     * does not hold, the item that chain could not get past, together with the planner's reason
     * ({@link CraftFetchQuestion#reasonItem()}, {@link CraftFetchQuestion#chainRefusal()}) — and the measurement itself
     * for the screen's question.
     */
    public static TerminalCraftOutcome measured(TerminalCraftResult result, CraftFetchQuestion<ItemKey> question) {
        Objects.requireNonNull(question, "question");
        return new TerminalCraftOutcome(result, question.possible(), question.reasonItem(), question.chainRefusal(),
                Optional.of(question));
    }

    /** Whether the action did what the player asked for ({@link TerminalCraftResult#isSuccess()}). */
    public boolean isSuccess() {
        return result.isSuccess();
    }
}
