package dev.wareworks.content.station;

import java.util.Objects;

import dev.wareworks.core.port.DoorState;
import dev.wareworks.core.port.PackagerSignAddress;
import net.minecraft.core.BlockPos;

/**
 * One package door as a terminal's screen is told about it (M27, issue #18, ADR-065 D9): where it is, what it is
 * called, how it stands and what the warehouse owes it. The value the menu compares between pushes and the
 * {@code TerminalDoorsPayload} carries.
 * <p>
 * <b>Bounded on construction</b>, so a row can always be written: the label is cut to Create's own address length
 * ({@link PackagerSignAddress#DISPLAY_LENGTH}, the same cut the port's goggles make, so a door reads identically on
 * both) and the rack address to {@value #MAX_RACK_ADDRESS_LENGTH} characters. A sign line may carry hundreds of
 * characters, and the string writer refuses one past its bound rather than cutting it.
 *
 * @param pos         the door's world position, which is all a request ever carries
 * @param rackAddress the door's rack address, e.g. {@code "A-12-03R"}, which tells two doors with the same sign apart
 * @param label       the sign address at its Packager, cut for display; empty for a door without a sign
 * @param labelCut    whether the label lost characters off its end, so the screen can mark it
 * @param state       how the door stood when it was last looked at
 * @param waiting     items the open requests to this door still wait for, the door's own redstone requests included
 * @param delivered   items of those requests already handed into the door's buffer
 */
public record TerminalDoorRow(BlockPos pos, String rackAddress, String label, boolean labelCut, DoorState state,
                              long waiting, long delivered) {
    /** Longest rack address a row carries, the bound {@code ProductionScreenState} gives a station's address. */
    public static final int MAX_RACK_ADDRESS_LENGTH = ProductionScreenState.MAX_ADDRESS_LENGTH;
    /** Longest label a row carries: Create's own address length. */
    public static final int MAX_LABEL_LENGTH = PackagerSignAddress.DISPLAY_LENGTH;

    public TerminalDoorRow {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(state, "state");
        pos = pos.immutable();
        rackAddress = bounded(rackAddress == null ? "" : rackAddress, MAX_RACK_ADDRESS_LENGTH);
        if (label == null)
            label = PackagerSignAddress.NONE;
        if (PackagerSignAddress.wouldShorten(label)) {
            label = PackagerSignAddress.shorten(label);
            labelCut = true;
        }
        waiting = Math.max(0L, waiting);
        delivered = Math.max(0L, delivered);
    }

    /** {@code text} cut to {@code max} characters, never through a surrogate pair. */
    private static String bounded(String text, int max) {
        if (text.length() <= max)
            return text;
        int end = max;
        if (Character.isHighSurrogate(text.charAt(end - 1)))
            end--;
        return text.substring(0, end);
    }
}
