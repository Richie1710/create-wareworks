package dev.wareworks.network;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.content.station.TerminalFluidOutcome;
import dev.wareworks.content.station.TerminalRequestOutcome;
import dev.wareworks.core.terminal.RequestConfirmation;
import dev.wareworks.core.terminal.RequestScope;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

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

    /**
     * The payload that answers a fluid click (M31, issue #21): the question, or the resolved request. A result names the
     * filled container of the first fill request it made; a refusal names the fluid's own bucket where it has one, and
     * an empty bucket otherwise. The key only gives the status row a name; nothing is requested by it.
     */
    public static CustomPacketPayload answer(TerminalFluidRequestPayload request, TerminalFluidOutcome outcome) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(outcome, "outcome");
        if (outcome.question().isPresent())
            return new TerminalFluidQuestionPayload(request.containerId(), outcome.question().get(), outcome.door());
        RequestResult result = outcome.result().orElseThrow();
        ItemKey key = result.request().map(accepted -> accepted.key()).orElseGet(() -> bucketOf(request.fluid()));
        return TerminalResultPayload.of(request.containerId(), key, result, outcome.door());
    }

    /** The item that names {@code fluid} in a refusal: its bucket, or an empty bucket for a fluid without one. */
    public static ItemKey bucketOf(FluidKey fluid) {
        Item bucket = fluid.getFluid().getBucket();
        return ItemKey.of(bucket == null || bucket == Items.AIR ? Items.BUCKET : bucket);
    }
}
