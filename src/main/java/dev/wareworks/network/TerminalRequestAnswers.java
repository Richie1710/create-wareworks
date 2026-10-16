package dev.wareworks.network;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.TerminalRequestOutcome;
import dev.wareworks.core.terminal.RequestConfirmation;
import dev.wareworks.core.terminal.RequestScope;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The answer to a {@link TerminalRequestPayload}: the terminal's question ({@link TerminalConfirmPayload}) when the click
 * crossed a boundary the player has not accepted, otherwise what became of it ({@link TerminalResultPayload}).
 * <p>
 * <b>Both echo the door the server used</b> (M27, issue #18, ADR-065 D8): the outcome carries the package door the
 * terminal really checked and measured the click for, empty for here, and the answer names exactly that one. The screen's
 * Yes sends it back and every feedback line names it, so a player who turned the door button in the meantime still
 * confirms and reads about the door they asked about. It is a method of its own so that a GameTest can assert the echo
 * without a network.
 */
public final class TerminalRequestAnswers {
    private TerminalRequestAnswers() {
    }

    /** The payload that answers {@code request}, which {@code outcome} resolved. */
    public static CustomPacketPayload answer(TerminalRequestPayload request, TerminalRequestOutcome outcome) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(outcome, "outcome");
        Optional<RequestConfirmation<ItemKey>> question = outcome.question();
        if (question.isPresent())
            return new TerminalConfirmPayload(request.containerId(), question.get(), outcome.door(),
                    RequestScope.CLICK);
        return TerminalResultPayload.of(request.containerId(), request.key(), outcome.result().orElseThrow(),
                outcome.door());
    }
}
