package dev.wareworks.content.station;

import java.util.Objects;
import java.util.Optional;

import dev.wareworks.content.item.ItemKey;

/**
 * What one craft click at a warehouse terminal became ({@code docs/warehouse-system.md} §3.4.5, M32, issue #24).
 * <p>
 * <b>One answer for a whole Shift-click.</b> {@link #result()} says how the click ended —
 * {@link TerminalCraftResult#CRAFTED} when it made every craft it asked for, otherwise the refusal it stopped at — and
 * {@link #crafted()} how many items it made before that. A refusal with {@code crafted() > 0} is therefore "crafted x5,
 * then stopped because of this", and a refusal with {@code crafted() == 0} moved nothing at all.
 * <p>
 * {@link #about()} is the item the sentence names: the result for {@link TerminalCraftResult#CRAFTED}, the missing
 * ingredient for {@link TerminalCraftResult#NOT_IN_BUFFER}, the item without room for
 * {@link TerminalCraftResult#NO_ROOM}. The other refusals name nothing.
 *
 * @param result  how the click ended
 * @param crafted how many items the click made, i.e. the result's count summed over every craft that happened
 * @param about   the item the sentence names; see above
 */
public record TerminalCraftOutcome(TerminalCraftResult result, int crafted, Optional<ItemKey> about) {
    public TerminalCraftOutcome {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(about, "about");
        crafted = Math.max(0, crafted);
    }

    /** An answer that crafted nothing and names nothing. */
    public static TerminalCraftOutcome of(TerminalCraftResult result) {
        return new TerminalCraftOutcome(result, 0, Optional.empty());
    }

    /** Whether the click did everything it asked for. */
    public boolean isSuccess() {
        return result.isSuccess();
    }
}
