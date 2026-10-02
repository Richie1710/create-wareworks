package dev.wareworks.content.station;

import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.terminal.RequestConfirmation;
import dev.wareworks.core.terminal.RequestScope;

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
 * @param cost     what the request really crossed, measured from the very snapshot it was made against (M23,
 *                 issue #19). It is the <b>same</b> measurement {@link #question()} would have carried, kept for a
 *                 request that <i>was</i> made: a list order spends its consent budget down by it, so one Yes cannot
 *                 pay for portion after portion ({@code ListOrder#spend}). Present only where a caller asked for it —
 *                 the list path — because measuring it is only free there, where the request path has to measure the
 *                 question anyway
 */
public record TerminalRequestOutcome(Optional<RequestResult> result,
                                     Optional<RequestConfirmation<ItemKey>> question,
                                     Optional<RequestConfirmation<ItemKey>> cost) {
    public TerminalRequestOutcome {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(cost, "cost");
        if (result.isPresent() == question.isPresent())
            throw new IllegalArgumentException("exactly one of result and question must be present");
    }

    /** The two-field form every caller from before M23 builds: a resolved request or a question, and no cost. */
    public TerminalRequestOutcome(Optional<RequestResult> result, Optional<RequestConfirmation<ItemKey>> question) {
        this(result, question, Optional.empty());
    }

    /** The request was resolved: accepted or refused. */
    public static TerminalRequestOutcome of(RequestResult result) {
        return new TerminalRequestOutcome(Optional.of(Objects.requireNonNull(result, "result")), Optional.empty());
    }

    /** The request was resolved, and what it crossed was measured on the way ({@link #cost()}). */
    public static TerminalRequestOutcome of(RequestResult result, @Nullable RequestConfirmation<ItemKey> cost) {
        return new TerminalRequestOutcome(Optional.of(Objects.requireNonNull(result, "result")), Optional.empty(),
                Optional.ofNullable(cost));
    }

    /**
     * Nothing was requested: the player is asked about {@code question} first.
     * <p>
     * The guard is measured against {@link dev.wareworks.core.terminal.RequestScope#LIST}, which is the weaker of the
     * two conditions and therefore holds for both: a question that crosses nothing <b>for any scope</b> is never
     * asked, while one portion of a list order may legitimately ask about production alone (M23, issue #19).
     */
    public static TerminalRequestOutcome asking(RequestConfirmation<ItemKey> question) {
        Objects.requireNonNull(question, "question");
        if (!question.required(RequestScope.LIST))
            throw new IllegalArgumentException("a question that crosses nothing is never asked: " + question);
        return new TerminalRequestOutcome(Optional.empty(), Optional.of(question));
    }

    /** Whether the terminal is asking instead of having requested anything. */
    public boolean isAsking() {
        return question.isPresent();
    }
}
