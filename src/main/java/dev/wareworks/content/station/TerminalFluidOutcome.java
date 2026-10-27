package dev.wareworks.content.station;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.content.controller.RequestResult;
import dev.wareworks.content.fluid.FluidKey;
import dev.wareworks.content.item.ItemKey;
import dev.wareworks.core.terminal.FluidQuestion;
import net.minecraft.core.BlockPos;

/**
 * What became of a click on a warehouse terminal's fluid cell (M31, issue #21, ADR-073): the question the terminal puts
 * first — which it always does for a fluid, {@link FluidQuestion} — or, once the player's answer covers it, the
 * resolved request. Exactly one of the two is present, as for an item click ({@link TerminalRequestOutcome}).
 *
 * @param result   the resolved request, empty while the terminal is asking; for an accepted one, the first fill
 *                 request it made and the containers granted over all of them
 * @param question what the request would mean, empty once it was resolved
 * @param door     the package door the click was for, as the terminal used it; empty for here
 */
public record TerminalFluidOutcome(Optional<RequestResult> result,
                                   Optional<FluidQuestion<ItemKey, FluidKey>> question, Optional<BlockPos> door) {
    public TerminalFluidOutcome {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(question, "question");
        door = door == null ? Optional.empty() : door.map(BlockPos::immutable);
        if (result.isPresent() == question.isPresent())
            throw new IllegalArgumentException("exactly one of result and question must be present");
    }

    /** The request was resolved: accepted or refused. */
    public static TerminalFluidOutcome of(RequestResult result) {
        return new TerminalFluidOutcome(Optional.of(Objects.requireNonNull(result, "result")), Optional.empty(),
                Optional.empty());
    }

    /** Nothing was requested: the player is asked about {@code question} first. */
    public static TerminalFluidOutcome asking(FluidQuestion<ItemKey, FluidKey> question) {
        return new TerminalFluidOutcome(Optional.empty(), Optional.of(Objects.requireNonNull(question, "question")),
                Optional.empty());
    }

    /** This outcome, for a click that named {@code next}; empty for one that delivers here. */
    public TerminalFluidOutcome withDoor(Optional<BlockPos> next) {
        return new TerminalFluidOutcome(result, question, next);
    }

    /** Whether the terminal is asking instead of having requested anything. */
    public boolean isAsking() {
        return question.isPresent();
    }
}
