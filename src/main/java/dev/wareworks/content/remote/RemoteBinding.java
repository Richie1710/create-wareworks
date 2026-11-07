package dev.wareworks.content.remote;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.world.InteractionHand;

/**
 * What ties a terminal screen opened from afar to the remote that opened it (issue #44, ADR-077): the hand the remote
 * was used from and the identity of the terminal it was linked to. Server side only; the client menu knows no more
 * than that it is a remote one.
 * <p>
 * The screen stays open only while that hand still holds a remote linked to that terminal
 * ({@link RemoteAccess#stillHolds}), which is one look at one hand rather than a search of the inventory: a remote
 * moved into another slot, dropped or put into a chest closes the screen.
 *
 * @param hand       the hand the remote was used from
 * @param terminalId the identity of the terminal the remote was linked to
 */
public record RemoteBinding(InteractionHand hand, UUID terminalId) {
    public RemoteBinding {
        Objects.requireNonNull(hand, "hand");
        Objects.requireNonNull(terminalId, "terminalId");
    }
}
