package dev.wareworks.content.station;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.terminal.ListOrderConfirmation;

/**
 * What a player's action on a terminal's clipboard order became, with the question when there is one
 * ({@code docs/warehouse-system.md} §3.4.4, M23, issue #19).
 * <p>
 * It is the {@link TerminalRequestOutcome} of a list: {@link TerminalListResult#ASKING} carries the measured
 * {@link ListOrderConfirmation} and means that <b>nothing was started</b> — no request, no production order, no
 * promise — while every other result carries no question at all.
 *
 * @param result   what happened
 * @param question what the whole list would really cost, present exactly for {@link TerminalListResult#ASKING}
 */
public record TerminalListOutcome(TerminalListResult result,
                                  Optional<ListOrderConfirmation<ItemKey>> question) {
    public TerminalListOutcome {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(question, "question");
        if (result.isAsking() != question.isPresent())
            throw new IllegalArgumentException("a question belongs to ASKING and to nothing else: " + result);
    }

    /** A plain answer with no question behind it. */
    public static TerminalListOutcome of(TerminalListResult result) {
        return new TerminalListOutcome(result, Optional.empty());
    }

    /** Nothing was started: the player is asked about {@code question} first. */
    public static TerminalListOutcome asking(ListOrderConfirmation<ItemKey> question) {
        return new TerminalListOutcome(TerminalListResult.ASKING,
                Optional.of(Objects.requireNonNull(question, "question")));
    }

    /** Whether the action did what the player asked for. */
    public boolean isSuccess() {
        return result.isSuccess();
    }
}
