package dev.wareworks.content.station;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import dev.wareworks.core.terminal.ListOrderState;
import net.minecraft.core.BlockPos;

/**
 * How far a package-mode terminal's <b>address order</b> has got, in the bounded form its screens are given (issue
 * #40, owner decision E11): the boxes it fetches, how many arrived, how many were taken by someone else meanwhile,
 * where they go, and the addresses it serves.
 * <p>
 * Derived state, rebuilt from {@code TerminalPackageOrder} whenever it is asked for and compared by value, so a
 * terminal without an address order (every terminal of a world without package mode) costs no packet
 * ({@code WarehouseTerminalMenu#broadcastChanges}).
 *
 * @param active    whether an address order exists; everything else is empty when not
 * @param id        the order's id, which the line's cancel mark sends back ({@code ProductionCancelPayload})
 * @param state     where the order stands
 * @param wanted    boxes the order fetches
 * @param delivered boxes that arrived
 * @param inFlight  boxes open requests still owe it
 * @param gone      boxes that were taken out by somebody else before the crane came for them, never fetched
 * @param door      the package door the boxes go to; empty for this terminal's own buffer
 * @param addresses the addresses the order serves, at most {@value #MAX_ADDRESSES}
 */
public record TerminalPackageOrderState(boolean active, UUID id, ListOrderState state, long wanted, long delivered,
                                        long inFlight, long gone, Optional<BlockPos> door, List<String> addresses) {
    /** Most addresses one state names; an order that serves more says so by its count only. */
    public static final int MAX_ADDRESSES = 4;
    /** Longest address a state carries. */
    public static final int MAX_ADDRESS_LENGTH = 64;

    /** No address order on this terminal. */
    public static final TerminalPackageOrderState NONE = new TerminalPackageOrderState(false, new UUID(0L, 0L),
            ListOrderState.DONE, 0L, 0L, 0L, 0L, Optional.empty(), List.of());

    public TerminalPackageOrderState {
        Objects.requireNonNull(id, "id");
        if (state == null)
            state = ListOrderState.DONE;
        door = door == null ? Optional.empty() : door.map(BlockPos::immutable);
        wanted = Math.max(0L, wanted);
        delivered = Math.max(0L, Math.min(delivered, wanted));
        inFlight = Math.max(0L, Math.min(inFlight, wanted - delivered));
        gone = Math.max(0L, gone);
        addresses = addresses == null ? List.of()
                : addresses.stream().limit(MAX_ADDRESSES).map(TerminalPackageOrderState::bounded).toList();
        if (!active) {
            id = new UUID(0L, 0L);
            state = ListOrderState.DONE;
            wanted = 0L;
            delivered = 0L;
            inFlight = 0L;
            gone = 0L;
            door = Optional.empty();
            addresses = List.of();
        }
    }

    private static String bounded(String address) {
        String text = address == null ? "" : address;
        return text.length() <= MAX_ADDRESS_LENGTH ? text : text.substring(0, MAX_ADDRESS_LENGTH);
    }

    /** Whether the order still has work to do. */
    public boolean isOpen() {
        return active && state.isOpen();
    }

    /** Boxes the order still waits for: neither arrived nor taken. */
    public long outstanding() {
        return Math.max(0L, wanted - delivered);
    }
}
