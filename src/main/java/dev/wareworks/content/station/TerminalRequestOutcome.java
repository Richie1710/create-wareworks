package dev.wareworks.content.station;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.terminal.RequestConfirmation;

/**
 * What became of a click on a warehouse terminal's item ({@code docs/warehouse-system.md} §3.4.2, §3.6.6): either the
 * request was resolved — accepted or refused, {@link RequestResult} — or the terminal has a <b>question</b> first and
 * has made no request at all.
 * <p>
 * Exactly one of the two is present, and that is the whole point of the type: a question is not a refusal (nothing is
 * wrong, and nothing is remembered in the station's last-rejection), and it is not an acceptance either (no item is
 * promised, no order is started, no budget beyond the click itself is spent). Before M15 part 2 a click had only two
 * possible endings, and folding the third into {@link RequestResult} would have made every reader of a rejection
 * handle a case that is not a rejection.
 *
 * @param result   what the controller answered, empty while the terminal is asking
 * @param question what the request would cross, empty when it was resolved
 */
public record TerminalRequestOutcome(Optional<RequestResult> result,
                                     Optional<RequestConfirmation<ItemKey>> question) {
    public TerminalRequestOutcome {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(question, "question");
        if (result.isPresent() == question.isPresent())
            throw new IllegalArgumentException("exactly one of result and question must be present");
    }

    /** The request was resolved: accepted or refused. */
    public static TerminalRequestOutcome of(RequestResult result) {
        return new TerminalRequestOutcome(Optional.of(Objects.requireNonNull(result, "result")), Optional.empty());
    }

    /** Nothing was requested: the player is asked about {@code question} first. */
    public static TerminalRequestOutcome asking(RequestConfirmation<ItemKey> question) {
        Objects.requireNonNull(question, "question");
        if (!question.required())
            throw new IllegalArgumentException("a question that crosses nothing is never asked: " + question);
        return new TerminalRequestOutcome(Optional.empty(), Optional.of(question));
    }

    /** Whether the terminal is asking instead of having requested anything. */
    public boolean isAsking() {
        return question.isPresent();
    }
}
