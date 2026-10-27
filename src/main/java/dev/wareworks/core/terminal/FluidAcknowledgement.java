package dev.wareworks.core.terminal;

import java.util.List;
import java.util.Objects;

/**
 * A player's answer to a {@link FluidQuestion} (M31, issue #21, ADR-073): the served amount, the containers of every
 * portion and the containers to be made, exactly as the question named them.
 * <p>
 * <b>Covering is equality.</b> An item click's acknowledgement covers any cost up to the numbers it names
 * ({@link RequestAcknowledgement#covers}); this one covers only the very question it was given. Serving <b>more</b>
 * than the player agreed to costs containers they did not agree to spend, and serving less is not what they asked for,
 * so both are a new question. The server measures every answer again ({@code WarehouseControllerBlockEntity
 * #requestFill}), and a warehouse that moved in between — an empty bucket taken, another request promised the lava — is
 * asked about again rather than carried out.
 * <p>
 * There is no "any" answer: a fluid request always asks ({@link FluidQuestion}).
 *
 * @param given         whether the player answered at all; {@link #NONE} is a first click
 * @param servedMb      the served amount the player accepted
 * @param portionCounts the containers per portion the player accepted, in plan order
 * @param makeCount     the containers the player accepted having made first
 */
public record FluidAcknowledgement(boolean given, long servedMb, List<Integer> portionCounts, int makeCount) {
    /** No answer: a first click, which makes the terminal ask. */
    public static final FluidAcknowledgement NONE = new FluidAcknowledgement(false, 0L, List.of(), 0);

    public FluidAcknowledgement {
        servedMb = Math.max(0L, servedMb);
        Objects.requireNonNull(portionCounts, "portionCounts");
        portionCounts = List.copyOf(portionCounts.size() <= ContainerPlan.MAX_PORTIONS ? portionCounts
                : portionCounts.subList(0, ContainerPlan.MAX_PORTIONS));
        makeCount = Math.max(0, makeCount);
    }

    /** The answer that accepts exactly {@code question}. */
    public static FluidAcknowledgement of(FluidQuestion<?, ?> question) {
        Objects.requireNonNull(question, "question");
        return new FluidAcknowledgement(true, question.servedMb(), question.portionCounts(), question.makeCount());
    }

    /**
     * Whether this answer accepts {@code question}: given, and naming exactly its served amount, its containers per
     * portion and its containers to be made. A question that serves nothing is never covered.
     */
    public boolean covers(FluidQuestion<?, ?> question) {
        Objects.requireNonNull(question, "question");
        return given && question.canFetch() && servedMb == question.servedMb()
                && portionCounts.equals(question.portionCounts()) && makeCount == question.makeCount();
    }
}
